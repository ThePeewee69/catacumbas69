package com.catacumbas69.passario.model;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One configuration change, stored in the shared {@code audit_logs} collection with the same fields
 * Pulsário uses, so both applications' changes appear in one audit log (OBS-6). Indexes are owned by
 * Pulsário.
 */
@Getter
@Document(collection = "audit_logs")
public class AuditRecord {

    /** Value of {@link #application} for entries written by Passário. */
    public static final String APPLICATION = "passario";

    @Id
    private String id;

    private Instant timestamp;

    /** Client IP address ({@code X-Forwarded-For} from Pulsário), or {@code passario} for automatic changes. */
    private String user;

    /** {@code AuditAction} name. */
    private String action;

    /** Application that wrote the entry. */
    private String application;

    /** Source topic of the bridge. */
    private String topic;

    /** Details: bridge id and name, definition before and after the change. Field name shared with Pulsário. */
    private Map<String, Object> topicProperties;

    /** For Spring Data. */
    protected AuditRecord() {
    }

    /**
     * Creates an entry.
     *
     * @param timestamp when the change happened
     * @param user who made it
     * @param action what was done
     * @param topic source topic of the bridge, or {@code null}
     * @param details bridge id, name, before and after
     */
    public AuditRecord(Instant timestamp, String user, String action, String topic, Map<String, Object> details) {
        this.timestamp = timestamp;
        this.user = user;
        this.action = action;
        this.application = APPLICATION;
        this.topic = topic;
        this.topicProperties = details;
    }
}
