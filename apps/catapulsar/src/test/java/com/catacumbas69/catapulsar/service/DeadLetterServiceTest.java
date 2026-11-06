package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.consumers.DeadLetters;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

@SuppressWarnings("unchecked")
class DeadLetterServiceTest {

    private static final String DLQ = "persistent://catacumbas69/cataudit/audit-cataudit-DLQ";
    private static final String ORIGIN = "persistent://catacumbas69/cataudit/audit";

    private final PulsarClient client = mock(PulsarClient.class);
    private final ProducerCache producers = mock(ProducerCache.class);
    private final MessageService messages = mock(MessageService.class);
    private final AuditService audit = mock(AuditService.class);
    private final Consumer<byte[]> consumer = mock(Consumer.class);
    private final Producer<byte[]> producer = mock(Producer.class);
    private final TypedMessageBuilder<byte[]> builder = mock(TypedMessageBuilder.class, RETURNS_SELF);
    private final DeadLetterService service = new DeadLetterService(client, producers, messages, audit);

    DeadLetterServiceTest() throws Exception {
        ConsumerBuilder<byte[]> consumerBuilder = mock(ConsumerBuilder.class, RETURNS_SELF);
        when(client.newConsumer(any(org.apache.pulsar.client.api.Schema.class))).thenReturn(consumerBuilder);
        when(consumerBuilder.subscribe()).thenReturn(consumer);
        when(consumer.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
        when(producers.get(ORIGIN)).thenReturn(producer);
        when(producer.newMessage()).thenReturn(builder);
    }

    @Test
    void sendBack_findsTheMessage_resendsItWithoutTheDeadLetterProperties_andAcknowledgesOnlyIt() throws Exception {
        Message<byte[]> other = message("1:1:-1", Map.of(DeadLetters.REAL_TOPIC, ORIGIN));
        Message<byte[]> wanted = message("1:2:-1", Map.of(DeadLetters.REAL_TOPIC, ORIGIN,
                DeadLetters.ORIGIN_MESSAGE_ID, "9:9:-1", "trace", "abc"));
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(other, wanted);

        assertThat(service.sendBack(DLQ, "1:2:-1", "10.0.0.1")).isEqualTo(ORIGIN);

        verify(builder).properties(Map.of("trace", "abc"));
        verify(builder).send();
        verify(consumer).acknowledge(wanted);
        verify(consumer, never()).acknowledge(other);
        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.SEND_BACK_DEAD_LETTER), eq(DLQ), anyMap());
    }

    @Test
    void discard_acknowledgesTheMessage_withoutSendingIt() throws Exception {
        Message<byte[]> wanted = message("1:2:-1", Map.of());
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(wanted);

        service.discard(DLQ, "1:2:-1", "10.0.0.1");

        verify(consumer).acknowledge(wanted);
        verify(builder, never()).send();
        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.DISCARD_DEAD_LETTER), eq(DLQ), anyMap());
    }

    @Test
    void answers404_whenTheMessageIsNotThereAnyMore() throws Exception {
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(null);

        assertThatThrownBy(() -> service.discard(DLQ, "1:2:-1", "10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not in the dead-letter topic");
        verify(consumer).closeAsync();
    }

    @Test
    void sendBackAll_leavesMessagesWithoutAnOrigin() throws Exception {
        Message<byte[]> withOrigin = message("1:1:-1", Map.of(DeadLetters.REAL_TOPIC, ORIGIN));
        Message<byte[]> without = message("1:2:-1", Map.of());
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(withOrigin, without, null);

        assertThat(service.sendBackAll(DLQ, "10.0.0.1")).containsEntry("sent", 1).containsEntry("left", 1);
        verify(consumer).acknowledge(withOrigin);
        verify(consumer, never()).acknowledge(without);
    }

    @Test
    void discardAll_clearsTheBacklogOfTheDlqSubscription() throws Exception {
        service.discardAll(DLQ, "10.0.0.1");

        verify(messages).clearBacklog(DLQ, DeadLetters.SUBSCRIPTION);
    }

    private static Message<byte[]> message(String id, Map<String, String> properties) {
        Message<byte[]> message = mock(Message.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(id);
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getData()).thenReturn("x".getBytes());
        when(message.getProperties()).thenReturn(properties);
        properties.forEach((key, value) -> when(message.getProperty(key)).thenReturn(value));
        return message;
    }
}
