package com.catacumbas69.catacommons.pulsar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class RetryingSenderTest {

    private static final String TOPIC = "persistent://catacumbas69/catapulsar/events";

    private final PulsarClient client = mock(PulsarClient.class);
    private final ProducerBuilder<String> builder = mock(ProducerBuilder.class, RETURNS_SELF);
    private final Producer<String> producer = mock(Producer.class);
    private final TypedMessageBuilder<String> message = mock(TypedMessageBuilder.class, RETURNS_SELF);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RetryingSender<String> sender = new RetryingSender<>(client, Schema.STRING, TOPIC,
            List.of(Duration.ZERO, Duration.ZERO), Duration.ofSeconds(1), scheduler, value -> value, registry);

    RetryingSenderTest() {
        when(client.newProducer(any(Schema.class))).thenReturn(builder);
        when(producer.newMessage()).thenReturn(message);
    }

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    @Test
    void send_retries_untilTheProducerCanBeCreated() {
        when(builder.createAsync()).thenReturn(failed(), CompletableFuture.completedFuture(producer));
        when(message.sendAsync()).thenReturn(CompletableFuture.completedFuture(mock(MessageId.class)));

        sender.send("k", "v");

        awaitCount("sent", 1);
        assertThat(count("retried")).isEqualTo(1);
        verify(builder, times(2)).createAsync();
        verify(message).key("k");
        verify(message).value("v");
    }

    @Test
    void send_givesUp_afterTheLastRetry() {
        when(builder.createAsync()).thenReturn(CompletableFuture.completedFuture(producer));
        when(message.sendAsync()).thenReturn(failed(), failed(), failed());

        sender.send("k", "v");

        awaitCount("failed", 1);
        assertThat(count("retried")).isEqualTo(2);
        assertThat(count("sent")).isZero();
        verify(builder, times(1)).createAsync();
    }

    @Test
    void send_afterClose_neverThrows() {
        sender.close();

        sender.send("k", "v");

        awaitCount("failed", 1);
    }

    private static <T> CompletableFuture<T> failed() {
        return CompletableFuture.failedFuture(new PulsarClientException("down"));
    }

    private double count(String outcome) {
        return registry.get("catacommons.messages").tag("outcome", outcome).counter().count();
    }

    private void awaitCount(String outcome, int expected) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> count(outcome) == expected);
    }
}
