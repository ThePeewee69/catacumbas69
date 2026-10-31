package com.catacumbas69.passario.service;

import static com.catacumbas69.passario.TestBridges.DEST_A;
import static com.catacumbas69.passario.TestBridges.DEST_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.passario.TestBridges;
import com.catacumbas69.passario.TestProperties;
import com.catacumbas69.passario.model.BridgeDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class BridgeWorkerTest {

    private static final Duration WAIT = Duration.ofSeconds(3);

    private final PulsarClient client = mock(PulsarClient.class);
    private final Consumer<byte[]> consumer = mock(Consumer.class);
    private final Producer<byte[]> producerA = mock(Producer.class);
    private final Producer<byte[]> producerB = mock(Producer.class);
    private final TypedMessageBuilder<byte[]> messageA = mock(TypedMessageBuilder.class, RETURNS_SELF);
    private final TypedMessageBuilder<byte[]> messageB = mock(TypedMessageBuilder.class, RETURNS_SELF);
    private final ConsumerBuilder<byte[]> consumerBuilder = mock(ConsumerBuilder.class, RETURNS_SELF);

    private final TrafficWriter trafficWriter = mock(TrafficWriter.class);
    private final TrafficRecorder traffic = new TrafficRecorder(trafficWriter, Clock.systemUTC());

    private BridgeWorker worker;

    @BeforeEach
    void wirePulsarMocks() throws PulsarClientException {
        ProducerBuilder<byte[]> builderA = mock(ProducerBuilder.class, RETURNS_SELF);
        ProducerBuilder<byte[]> builderB = mock(ProducerBuilder.class, RETURNS_SELF);
        when(builderA.createAsync()).thenReturn(CompletableFuture.completedFuture(producerA));
        when(builderB.createAsync()).thenReturn(CompletableFuture.completedFuture(producerB));
        // Producers are created in destination order: DEST_A, then DEST_B.
        when(client.newProducer()).thenReturn(builderA, builderB);
        when(client.newConsumer()).thenReturn(consumerBuilder);
        when(consumerBuilder.subscribeAsync()).thenReturn(CompletableFuture.completedFuture(consumer));

        when(producerA.newMessage()).thenReturn(messageA);
        when(producerB.newMessage()).thenReturn(messageB);
        for (Producer<byte[]> producer : new Producer[] {producerA, producerB}) {
            when(producer.flushAsync()).thenReturn(CompletableFuture.completedFuture(null));
            when(producer.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
        }
        when(consumer.acknowledgeAsync(any(MessageId.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(consumer.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(null);
    }

    @AfterEach
    void stopWorker() {
        if (worker != null) {
            worker.stop(Duration.ofMillis(200));
        }
    }

    @Test
    void forwardsEachMessageToEveryDestination_andAcksAfterAllSucceed() throws Exception {
        Message<byte[]> m1 = message("m1");
        Message<byte[]> m2 = message("m2");
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(m1, m2, (Message<byte[]>) null);
        sendsSucceed(messageA);
        sendsSucceed(messageB);

        worker = start(TestBridges.unordered("1", DEST_A, DEST_B));

        await().atMost(WAIT).untilAsserted(() -> {
            verify(consumer).acknowledgeAsync(m1.getMessageId());
            verify(consumer).acknowledgeAsync(m2.getMessageId());
            assertThat(worker.getStatus().messagesForwarded()).isEqualTo(2);
        });
        verify(messageA, times(2)).sendAsync();
        verify(messageB, times(2)).sendAsync();
        assertThat(worker.getStatus().running()).isTrue();
    }

    @Test
    void countsMessagesAndBytes_perSourceAndDestinationTopic() throws Exception {
        Message<byte[]> m1 = message("abc");
        Message<byte[]> m2 = message("de");
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(m1, m2, (Message<byte[]>) null);
        sendsSucceed(messageA);
        sendsSucceed(messageB);

        worker = start(TestBridges.unordered("1", DEST_A, DEST_B));
        await().atMost(WAIT).untilAsserted(() -> assertThat(worker.getStatus().messagesForwarded()).isEqualTo(2));
        traffic.flush();

        ArgumentCaptor<Collection<TopicTraffic.Drained>> written = ArgumentCaptor.forClass(Collection.class);
        verify(trafficWriter).write(written.capture());
        Map<String, Long> messages = new HashMap<>();
        Map<String, Long> bytes = new HashMap<>();
        written.getValue().forEach(entry -> {
            String key = entry.series().role() + " " + entry.series().topic();
            messages.merge(key, entry.messages(), Long::sum);
            bytes.merge(key, entry.bytes(), Long::sum);
        });
        assertThat(messages).containsOnly(
                entry("SOURCE " + TestBridges.SOURCE, 2L), entry("DESTINATION " + DEST_A, 2L),
                entry("DESTINATION " + DEST_B, 2L));
        assertThat(bytes).containsEntry("SOURCE " + TestBridges.SOURCE, 5L);
    }

    @Test
    void doesNotCountSource_whenADestinationFails() throws Exception {
        Message<byte[]> m1 = message("m1");
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(m1, (Message<byte[]>) null);
        sendsSucceed(messageA);
        when(messageB.sendAsync()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        worker = start(TestBridges.unordered("1", DEST_A, DEST_B));
        await().atMost(WAIT).untilAsserted(() -> verify(consumer).negativeAcknowledge(m1));
        traffic.flush();

        ArgumentCaptor<Collection<TopicTraffic.Drained>> written = ArgumentCaptor.forClass(Collection.class);
        verify(trafficWriter).write(written.capture());
        assertThat(written.getValue()).extracting(entry -> entry.series().topic()).containsExactly(DEST_A);
    }

    @Test
    void copiesKeyPropertiesAndEventTime() throws Exception {
        Message<byte[]> m1 = message("m1");
        when(m1.hasKey()).thenReturn(true);
        when(m1.getKey()).thenReturn("user-7");
        when(m1.getProperties()).thenReturn(Map.of("trace", "abc"));
        when(m1.getEventTime()).thenReturn(1234L);
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(m1, (Message<byte[]>) null);
        sendsSucceed(messageA);

        worker = start(TestBridges.unordered("1", DEST_A));

        await().atMost(WAIT).untilAsserted(() -> verify(consumer).acknowledgeAsync(m1.getMessageId()));
        verify(messageA).key("user-7");
        verify(messageA).properties(Map.of("trace", "abc"));
        verify(messageA).eventTime(1234L);
    }

    @Test
    void unordered_negativelyAcksMessage_whenAnyDestinationFails() throws Exception {
        Message<byte[]> m1 = message("m1");
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(m1, (Message<byte[]>) null);
        sendsSucceed(messageA);
        when(messageB.sendAsync()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        worker = start(TestBridges.unordered("1", DEST_A, DEST_B));

        await().atMost(WAIT).untilAsserted(() -> verify(consumer).negativeAcknowledge(m1));
        verify(consumer, never()).acknowledgeAsync(m1.getMessageId());
        verify(consumer, never()).redeliverUnacknowledgedMessages();
        assertThat(worker.getStatus().errors()).isEqualTo(1);
        assertThat(worker.getStatus().lastError()).isEqualTo("down");
    }

    @Test
    void ordered_replaysUnackedMessagesInOrder_insteadOfNacking() throws Exception {
        Message<byte[]> m1 = message("m1");
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(m1, (Message<byte[]>) null);
        when(messageA.sendAsync()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        worker = start(TestBridges.ordered("1", DEST_A));

        await().atMost(WAIT).untilAsserted(() -> verify(consumer, atLeastOnce()).redeliverUnacknowledgedMessages());
        verify(consumer, never()).negativeAcknowledge(any(Message.class));
        verify(consumer, never()).acknowledgeAsync(any(MessageId.class));
    }

    @Test
    void limitsUnconfirmedMessages_toMaxInFlight() throws Exception {
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenAnswer(invocation -> message("next"));
        when(messageA.sendAsync()).thenAnswer(invocation -> new CompletableFuture<MessageId>());
        BridgeDocument bridge = TestBridges.unordered("1", DEST_A);
        bridge.setMaxInFlight(3);

        worker = start(bridge);

        verify(consumer, after(500).times(3)).receive(anyInt(), any(TimeUnit.class));
        assertThat(worker.getStatus().inFlight()).isEqualTo(3);
    }

    @Test
    void start_closesCreatedProducers_whenSubscribeFails() {
        when(consumerBuilder.subscribeAsync())
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no subscription")));
        worker = new BridgeWorker(TestBridges.unordered("1", DEST_A, DEST_B), client,
                TestProperties.bridge(10), new BridgeMetrics(new SimpleMeterRegistry(), "b"), traffic);

        CompletableFuture<Void> started = worker.start();

        assertThat(started).isCompletedExceptionally();
        assertThat(worker.getState()).isEqualTo(BridgeState.FAILED);
        verify(producerA).closeAsync();
        verify(producerB).closeAsync();
    }

    @Test
    void stop_closesEverything_andReportsStopped() throws Exception {
        worker = start(TestBridges.unordered("1", DEST_A));

        worker.stop(Duration.ofSeconds(1));

        assertThat(worker.getState()).isEqualTo(BridgeState.STOPPED);
        verify(producerA).flushAsync();
        verify(producerA).closeAsync();
        verify(consumer).closeAsync();
    }

    private BridgeWorker start(BridgeDocument bridge) throws Exception {
        BridgeWorker started = new BridgeWorker(bridge, client, TestProperties.bridge(10),
                new BridgeMetrics(new SimpleMeterRegistry(), bridge.getName()), traffic);
        started.start().get(1, TimeUnit.SECONDS);
        return started;
    }

    private static void sendsSucceed(TypedMessageBuilder<byte[]> builder) {
        when(builder.sendAsync()).thenAnswer(invocation -> CompletableFuture.completedFuture(mock(MessageId.class)));
    }

    private static Message<byte[]> message(String payload) {
        Message<byte[]> message = mock(Message.class);
        MessageId id = mock(MessageId.class);
        when(message.getData()).thenReturn(payload.getBytes());
        when(message.getMessageId()).thenReturn(id);
        when(message.getProperties()).thenReturn(Map.of());
        return message;
    }
}
