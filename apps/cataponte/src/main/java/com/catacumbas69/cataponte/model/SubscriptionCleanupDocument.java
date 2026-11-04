package com.catacumbas69.cataponte.model;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A Pulsar subscription to delete because the bridge that used it was deleted, or now reads another topic or
 * subscription (orch.md decision 60). Kept until the subscription is gone, so a failure is retried, also after a
 * restart and on any replica.
 */
@Getter
@Setter
@Document(collection = SubscriptionCleanupDocument.COLLECTION)
public class SubscriptionCleanupDocument {

    /** Pending subscription deletions. */
    public static final String COLLECTION = "cataponte_subscription_cleanups";

    /** {@code <topic>|<subscription>}. */
    @Id
    private String id;

    private String topic;

    private String subscription;

    /** Bridge that used the subscription. */
    private String bridgeId;

    private String bridgeName;

    /** Who deleted or changed the bridge, for the audit event. */
    private String actor;

    private Instant requestedAt;

    /** Not tried before this time: the bridge's workers stop first, and one replica works on it at a time. */
    private Instant notBefore;

    private int attempts;

    private String lastError;

    /**
     * Returns the id of a topic and subscription.
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @return the id
     */
    public static String idOf(String topic, String subscription) {
        return topic + "|" + subscription;
    }
}
