package com.catacumbas69.pulsario.model;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One audited user action, stored in the {@code audit_logs} collection of the {@code catacumbas69}
 * database. Indexes are created by {@code AuditIndexes}.
 */
@Document(collection = "audit_logs")
public class AuditEntry {

    @Id
    private String id;

    /** When the action happened. */
    private Instant timestamp;

    /** Client IP address (the application has no authentication yet). */
    private String user;

    /** {@code AuditAction} name, e.g. {@code PRODUCE} or {@code CREATE_BRIDGE}. */
    private String action;

    /** Fully-qualified topic the action applied to, or {@code null}. */
    private String topic;

    /** Message payload for produce actions, or {@code null}. */
    private String messagePayload;

    /** Message key for produce actions, or {@code null}. */
    private String messageKey;

    /** Extra details: subscription, count, bridge id, … */
    private Map<String, Object> topicProperties;

    /** Creates an empty entry (used by Spring Data). */
    public AuditEntry() {
    }

    /**
     * Creates an entry.
     *
     * @param timestamp when the action happened
     * @param user client IP address
     * @param action action name
     * @param topic topic, or {@code null}
     * @param messagePayload payload, or {@code null}
     * @param messageKey key, or {@code null}
     * @param topicProperties extra details, or {@code null}
     */
    public AuditEntry(Instant timestamp, String user, String action, String topic,
                      String messagePayload, String messageKey, Map<String, Object> topicProperties) {
        this.timestamp = timestamp;
        this.user = user;
        this.action = action;
        this.topic = topic;
        this.messagePayload = messagePayload;
        this.messageKey = messageKey;
        this.topicProperties = topicProperties;
    }

    public String getId() {
        return id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getUser() {
        return user;
    }

    public String getAction() {
        return action;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessagePayload() {
        return messagePayload;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Map<String, Object> getTopicProperties() {
        return topicProperties;
    }
}
