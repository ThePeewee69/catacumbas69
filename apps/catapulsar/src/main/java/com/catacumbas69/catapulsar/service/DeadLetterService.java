package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.consumers.DeadLetters;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Acts on the messages of a dead-letter topic (ADR 0015, orch.md decision 68): send one back to the topic it came
 * from ({@code REAL_TOPIC}), discard one, send all back or discard all. Each action is audited.
 *
 * <p>Any message can be chosen: a short-lived consumer on the DLQ's {@code dlq} subscription reads until it finds
 * the message id and acknowledges only that one; the others it read are delivered again when it closes. A message is
 * acknowledged only after it was sent back.
 */
@Service
public class DeadLetterService {

    /** Most messages read to find one, or sent back by "all". */
    static final int MAX_MESSAGES = 10_000;

    private static final int RECEIVE_MILLIS = 1500;
    private static final int RECEIVER_QUEUE = 200;

    private final PulsarClient client;
    private final ProducerCache producers;
    private final MessageService messages;
    private final AuditService audit;

    /**
     * Creates the service.
     *
     * @param client Pulsar client
     * @param producers producers for the original topics
     * @param messages clears the DLQ's backlog for "discard all"
     * @param audit audit log
     */
    public DeadLetterService(PulsarClient client, ProducerCache producers, MessageService messages,
                             AuditService audit) {
        this.client = client;
        this.producers = producers;
        this.messages = messages;
        this.audit = audit;
    }

    /**
     * Sends one dead letter back to the topic it came from, then removes it from the DLQ.
     *
     * @param deadLetterTopic the DLQ
     * @param messageId the message
     * @param user client IP address
     * @return the topic it was sent to
     * @throws PulsarClientException if Pulsar refuses
     */
    public String sendBack(String deadLetterTopic, String messageId, String user) throws PulsarClientException {
        Consumer<byte[]> consumer = open(deadLetterTopic);
        try {
            Message<byte[]> message = find(consumer, messageId);
            String target = origin(message);
            resend(message, target);
            consumer.acknowledge(message);
            audit.record(user, AuditAction.SEND_BACK_DEAD_LETTER, deadLetterTopic,
                    Map.<String, Object>of("messageId", messageId, "to", target));
            return target;
        } finally {
            consumer.closeAsync();
        }
    }

    /**
     * Removes one dead letter from the DLQ.
     *
     * @param deadLetterTopic the DLQ
     * @param messageId the message
     * @param user client IP address
     * @throws PulsarClientException if Pulsar refuses
     */
    public void discard(String deadLetterTopic, String messageId, String user) throws PulsarClientException {
        Consumer<byte[]> consumer = open(deadLetterTopic);
        try {
            consumer.acknowledge(find(consumer, messageId));
            audit.record(user, AuditAction.DISCARD_DEAD_LETTER, deadLetterTopic,
                    Map.<String, Object>of("messageId", messageId));
        } finally {
            consumer.closeAsync();
        }
    }

    /**
     * Sends every dead letter back to the topic it came from; one without an origin stays in the DLQ.
     *
     * @param deadLetterTopic the DLQ
     * @param user client IP address
     * @return how many were sent back ({@code sent}) and left ({@code left})
     * @throws PulsarClientException if Pulsar refuses
     */
    public Map<String, Integer> sendBackAll(String deadLetterTopic, String user) throws PulsarClientException {
        Consumer<byte[]> consumer = open(deadLetterTopic);
        int sent = 0;
        int left = 0;
        try {
            for (int i = 0; i < MAX_MESSAGES; i++) {
                Message<byte[]> message = consumer.receive(RECEIVE_MILLIS, TimeUnit.MILLISECONDS);
                if (message == null) {
                    break;
                }
                String target = message.getProperty(DeadLetters.REAL_TOPIC);
                if (target == null || target.isBlank()) {
                    left++;
                    continue;
                }
                resend(message, target);
                consumer.acknowledge(message);
                sent++;
            }
        } finally {
            consumer.closeAsync();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sent", sent);
            details.put("left", left);
            audit.record(user, AuditAction.SEND_BACK_DEAD_LETTER, deadLetterTopic, details);
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("sent", sent);
        result.put("left", left);
        return result;
    }

    /**
     * Removes every dead letter from the DLQ.
     *
     * @param deadLetterTopic the DLQ
     * @param user client IP address
     * @throws PulsarAdminException if Pulsar refuses
     */
    public void discardAll(String deadLetterTopic, String user) throws PulsarAdminException {
        messages.clearBacklog(deadLetterTopic, DeadLetters.SUBSCRIPTION);
        audit.record(user, AuditAction.DISCARD_DEAD_LETTER, deadLetterTopic,
                Map.<String, Object>of("messageId", "*"));
    }

    private Consumer<byte[]> open(String deadLetterTopic) throws PulsarClientException {
        return client.newConsumer(Schema.BYTES)
                .topic(deadLetterTopic)
                .subscriptionName(DeadLetters.SUBSCRIPTION)
                .subscriptionType(SubscriptionType.Shared)
                .receiverQueueSize(RECEIVER_QUEUE)
                .consumerName("catapulsar-dlq-" + UUID.randomUUID().toString().substring(0, 8))
                .subscribe();
    }

    /** Reads until the message is found; the others read are left unacknowledged and delivered again. */
    private static Message<byte[]> find(Consumer<byte[]> consumer, String messageId) throws PulsarClientException {
        for (int i = 0; i < MAX_MESSAGES; i++) {
            Message<byte[]> message = consumer.receive(RECEIVE_MILLIS, TimeUnit.MILLISECONDS);
            if (message == null) {
                break;
            }
            if (message.getMessageId().toString().equals(messageId)) {
                return message;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Message " + messageId + " is not in the dead-letter topic any more (handled already?)");
    }

    private static String origin(Message<byte[]> message) {
        String target = message.getProperty(DeadLetters.REAL_TOPIC);
        if (target == null || target.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The message has no " + DeadLetters.REAL_TOPIC + " property: its original topic is unknown");
        }
        return target;
    }

    /** Sends a copy to the original topic, without the dead-letter properties. */
    private void resend(Message<byte[]> message, String target) throws PulsarClientException {
        Map<String, String> properties = new HashMap<>(message.getProperties());
        properties.remove(DeadLetters.REAL_TOPIC);
        properties.remove(DeadLetters.ORIGIN_MESSAGE_ID);
        properties.remove(DeadLetters.REASON);
        TypedMessageBuilder<byte[]> builder = producers.get(target).newMessage().value(message.getData())
                .properties(properties);
        if (message.hasKey()) {
            builder.key(message.getKey());
        }
        if (message.getEventTime() > 0) {
            builder.eventTime(message.getEventTime());
        }
        builder.send();
    }
}
