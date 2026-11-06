package com.catacumbas69.catacommons.consumers;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;

/**
 * Sends a message straight to its DLQ, without retrying it first: for a message that can never be handled, such as
 * one that is not valid at all (ADR 0015). It carries Pulsar's own dead-letter properties ({@code REAL_TOPIC},
 * {@code ORIGIN_MESSAGE_ID}) plus {@code DLQ_REASON}, so it looks like any other dead letter.
 */
public class DeadLetterWriter implements AutoCloseable {

    private final PulsarClient client;
    private final PulsarAdmin admin;
    private final Map<String, CompletableFuture<Producer<byte[]>>> producers = new ConcurrentHashMap<>();

    /**
     * Creates the writer.
     *
     * @param client Pulsar client
     * @param admin creates the DLQ and its subscription when needed
     */
    public DeadLetterWriter(PulsarClient client, PulsarAdmin admin) {
        this.client = client;
        this.admin = admin;
    }

    /**
     * Sends a message to the DLQ of its subscription.
     *
     * @param topic topic it was read from
     * @param subscription subscription it was read with
     * @param message the message
     * @param reason why it is dead-lettered
     * @return completes with the DLQ message id, or fails
     */
    public CompletableFuture<MessageId> send(String topic, String subscription, Message<byte[]> message,
                                             String reason) {
        String deadLetterTopic = DeadLetters.topicOf(topic, subscription);
        return producer(deadLetterTopic).thenCompose(producer -> {
            TypedMessageBuilder<byte[]> builder = producer.newMessage()
                    .value(message.getData())
                    .properties(message.getProperties())
                    .property(DeadLetters.REAL_TOPIC, topic)
                    .property(DeadLetters.ORIGIN_MESSAGE_ID, message.getMessageId().toString())
                    .property(DeadLetters.REASON, reason);
            if (message.hasKey()) {
                builder.key(message.getKey());
            }
            return builder.sendAsync();
        });
    }

    /** One producer per DLQ, created once; a failed creation is forgotten so the next message tries again. */
    private CompletableFuture<Producer<byte[]>> producer(String deadLetterTopic) {
        CompletableFuture<Producer<byte[]>> producer = producers.computeIfAbsent(deadLetterTopic, this::create);
        producer.whenComplete((created, error) -> {
            if (error != null) {
                producers.remove(deadLetterTopic, producer);
            }
        });
        return producer;
    }

    private CompletableFuture<Producer<byte[]>> create(String deadLetterTopic) {
        try {
            DeadLetters.ensure(admin, deadLetterTopic);
        } catch (PulsarAdminException e) {
            return CompletableFuture.failedFuture(e);
        }
        return client.newProducer(Schema.BYTES).topic(deadLetterTopic).createAsync();
    }

    /** Closes the producers. */
    @Override
    public void close() {
        producers.values().forEach(future -> future.thenAccept(Producer::closeAsync));
    }
}
