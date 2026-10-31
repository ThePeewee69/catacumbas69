package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catapulsar.TestProperties;
import com.catacumbas69.catapulsar.dto.MessageDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.Topics;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class MessageServiceTest {

    private static final String TOPIC = "persistent://a/b/c";

    private final PulsarAdmin admin = mock(PulsarAdmin.class);
    private final Topics topics = mock(Topics.class);
    private final ProducerCache producers = mock(ProducerCache.class);
    private final MessageService service = new MessageService(admin, producers, TestProperties.properties(10, 20));

    @BeforeEach
    void setUp() {
        when(admin.topics()).thenReturn(topics);
    }

    @Test
    void produce_setsKeyAndProperties_andReturnsMessageId() throws PulsarClientException {
        Producer<byte[]> producer = mock(Producer.class);
        TypedMessageBuilder<byte[]> message = mock(TypedMessageBuilder.class, RETURNS_SELF);
        MessageId id = mock(MessageId.class);
        when(id.toString()).thenReturn("1:2:-1");
        when(producers.get(TOPIC)).thenReturn(producer);
        when(producer.newMessage()).thenReturn(message);
        when(message.send()).thenReturn(id);

        String result = service.produce(TOPIC, "hello", "k1", Map.of("p", "v"));

        assertThat(result).isEqualTo("1:2:-1");
        verify(message).value("hello".getBytes(StandardCharsets.UTF_8));
        verify(message).key("k1");
        verify(message).properties(Map.of("p", "v"));
    }

    @Test
    void produce_withoutKey_doesNotSetKey() throws PulsarClientException {
        Producer<byte[]> producer = mock(Producer.class);
        TypedMessageBuilder<byte[]> message = mock(TypedMessageBuilder.class, RETURNS_SELF);
        when(producers.get(TOPIC)).thenReturn(producer);
        when(producer.newMessage()).thenReturn(message);
        when(message.send()).thenReturn(mock(MessageId.class));

        service.produce(TOPIC, "hello", " ", null);

        verify(message, never()).key(anyString());
        verify(message, never()).properties(any());
    }

    @Test
    void peek_capsCount_andMarksBinaryPayloads() throws PulsarAdminException {
        Message<byte[]> text = message("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        Message<byte[]> binary = message(new byte[] {(byte) 0xFF, (byte) 0xFE});
        when(topics.peekMessages(eq(TOPIC), eq("sub"), anyInt())).thenReturn(List.of(text, binary));

        List<MessageDTO> result = service.peek(TOPIC, "sub", 500);

        verify(topics).peekMessages(TOPIC, "sub", 20);
        assertThat(result).extracting(MessageDTO::binary).containsExactly(false, true);
        assertThat(result.get(0).payload()).isEqualTo("{\"a\":1}");
    }

    @Test
    void skip_delegatesToPulsar() throws PulsarAdminException {
        service.skip(TOPIC, "sub", 3);

        verify(topics).skipMessages(TOPIC, "sub", 3);
    }

    @Test
    void clearBacklog_delegatesToPulsar() throws PulsarAdminException {
        service.clearBacklog(TOPIC, "sub");

        verify(topics).skipAllMessages(TOPIC, "sub");
    }

    private static Message<byte[]> message(byte[] data) {
        Message<byte[]> message = mock(Message.class);
        when(message.getData()).thenReturn(data);
        when(message.getMessageId()).thenReturn(mock(MessageId.class));
        when(message.getProperties()).thenReturn(Map.of());
        return message;
    }
}
