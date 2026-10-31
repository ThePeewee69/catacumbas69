package com.catacumbas69.pulsario.service;

import com.catacumbas69.pulsario.config.PulsarioProperties;
import com.catacumbas69.pulsario.dto.MessageDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Produces, peeks and skips messages, and clears subscription backlogs.
 */
@Service
public class MessageService {

    private final PulsarAdmin admin;
    private final ProducerCache producers;
    private final int maxPeekCount;

    /**
     * Creates the service.
     *
     * @param admin Pulsar admin client
     * @param producers cached producers
     * @param properties application properties
     */
    public MessageService(PulsarAdmin admin, ProducerCache producers, PulsarioProperties properties) {
        this.admin = admin;
        this.producers = producers;
        this.maxPeekCount = properties.pulsar().maxPeekCount();
    }

    /**
     * Sends one message and waits for the broker's confirmation.
     *
     * @param topic fully-qualified topic
     * @param payload UTF-8 payload
     * @param key optional key
     * @param properties optional properties
     * @return the id assigned by the broker
     * @throws PulsarClientException if the message cannot be sent
     */
    public String produce(String topic, String payload, String key, Map<String, String> properties)
            throws PulsarClientException {
        TypedMessageBuilder<byte[]> message = producers.get(topic).newMessage()
                .value(payload.getBytes(StandardCharsets.UTF_8));
        if (StringUtils.hasText(key)) {
            message.key(key);
        }
        if (properties != null && !properties.isEmpty()) {
            message.properties(properties);
        }
        MessageId id = message.send();
        return id.toString();
    }

    /**
     * Returns the first messages of a subscription's backlog without consuming them.
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @param count requested number of messages; capped at {@code max-peek-count} (PERF-5)
     * @return the messages, oldest first
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public List<MessageDTO> peek(String topic, String subscription, int count) throws PulsarAdminException {
        int bounded = Math.max(1, Math.min(count, maxPeekCount));
        List<Message<byte[]>> messages = admin.topics().peekMessages(topic, subscription, bounded);
        return messages.stream().map(MessageService::toDto).toList();
    }

    /**
     * Moves a subscription's cursor forward by {@code count} messages (they will not be delivered).
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @param count number of messages to skip
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public void skip(String topic, String subscription, long count) throws PulsarAdminException {
        admin.topics().skipMessages(topic, subscription, count);
    }

    /**
     * Skips every message in a subscription's backlog: the cursor moves to the end of the topic.
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public void clearBacklog(String topic, String subscription) throws PulsarAdminException {
        admin.topics().skipAllMessages(topic, subscription);
    }

    private static MessageDTO toDto(Message<byte[]> message) {
        PayloadCodec.Decoded payload = PayloadCodec.decode(message.getData());
        return new MessageDTO(
                message.getMessageId().toString(),
                payload.text(),
                message.getKey(),
                message.getPublishTime(),
                message.getProperties(),
                payload.binary());
    }
}
