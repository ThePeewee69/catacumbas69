package com.catacumbas69.catacommons.consumers;

import com.catacumbas69.catacommons.settings.SharedSettings;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.apache.pulsar.client.api.MessageId;

/**
 * Dead-letter topics (ADR 0015, orch.md decision 68): {@code <topic>-<subscription>-DLQ} in the topic's namespace,
 * with the subscription {@code dlq}, whose backlog is the number of poison messages left to look at.
 */
public final class DeadLetters {

    /** Shared setting with the number of deliveries before a message goes to the DLQ. */
    public static final String MAX_DELIVERIES_KEY = "dlq.max-deliveries";

    /** Subscription created on every DLQ, so its messages are kept until someone acts. */
    public static final String SUBSCRIPTION = "dlq";

    /** Fewest deliveries allowed. */
    public static final int MIN_DELIVERIES = 2;

    /** Most deliveries allowed. */
    public static final int MAX_DELIVERIES = 100;

    /** Message property with the topic a dead letter came from (Pulsar's own name). */
    public static final String REAL_TOPIC = "REAL_TOPIC";

    /** Message property with the id of the original message (Pulsar's own name). */
    public static final String ORIGIN_MESSAGE_ID = "ORIGIN_MESSAGE_ID";

    /** Message property with why the message was dead-lettered without being retried. */
    public static final String REASON = "DLQ_REASON";

    private DeadLetters() {
    }

    /**
     * Returns the DLQ of a subscription.
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @return {@code <topic>-<subscription>-DLQ}
     */
    public static String topicOf(String topic, String subscription) {
        return topic + "-" + subscription + "-DLQ";
    }

    /**
     * Returns the policy for a consumer: a message delivered {@code maxDeliveries} times goes to the DLQ.
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @param maxDeliveries deliveries before the DLQ
     * @return the policy
     */
    public static DeadLetterPolicy policy(String topic, String subscription, int maxDeliveries) {
        return DeadLetterPolicy.builder()
                .maxRedeliverCount(Math.max(1, maxDeliveries - 1))
                .deadLetterTopic(topicOf(topic, subscription))
                .initialSubscriptionName(SUBSCRIPTION)
                .build();
    }

    /**
     * Returns the number of deliveries in force: the shared setting when it is a number in range, otherwise the
     * default.
     *
     * @param settings shared settings, or {@code null}
     * @param fallback the configured default
     * @return deliveries before the DLQ
     */
    public static int maxDeliveries(SharedSettings settings, int fallback) {
        if (settings == null) {
            return fallback;
        }
        return settings.get(MAX_DELIVERIES_KEY).map(value -> parse(value, fallback)).orElse(fallback);
    }

    /**
     * Parses the setting.
     *
     * @param value the setting's text
     * @param fallback used when it is not a number in range
     * @return deliveries before the DLQ
     */
    public static int parse(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.strip());
            return parsed < MIN_DELIVERIES || parsed > MAX_DELIVERIES ? fallback : parsed;
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    /**
     * Creates a DLQ and its {@code dlq} subscription if they do not exist yet, so a message sent there is kept.
     *
     * @param admin Pulsar admin client
     * @param deadLetterTopic the DLQ
     * @throws PulsarAdminException if Pulsar refuses for another reason than "already exists"
     */
    public static void ensure(PulsarAdmin admin, String deadLetterTopic) throws PulsarAdminException {
        try {
            admin.topics().createNonPartitionedTopic(deadLetterTopic);
        } catch (PulsarAdminException.ConflictException e) {
            // exists
        }
        try {
            admin.topics().createSubscription(deadLetterTopic, SUBSCRIPTION, MessageId.earliest);
        } catch (PulsarAdminException.ConflictException e) {
            // exists
        }
    }
}
