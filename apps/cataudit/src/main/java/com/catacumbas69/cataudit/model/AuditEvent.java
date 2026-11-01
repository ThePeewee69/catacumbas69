package com.catacumbas69.cataudit.model;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One audited action, stored in {@value #COLLECTION} (ADR 0009). It is the CloudEvent as received, with the
 * parts of its type split out for querying. {@code _id} is the CloudEvent {@code id}, so a redelivered or
 * duplicated event is stored once (ADR 0012).
 *
 * @param id CloudEvent id; {@code legacy-<ObjectId>} for entries copied from {@code audit_logs}
 * @param source CloudEvent source, {@code /catacumbas69/<app>}
 * @param type CloudEvent type, {@code com.catacumbas69.<app>.<entity>.<action>.v1}
 * @param app app that published it
 * @param entity what it is about, e.g. {@code topic}
 * @param action what happened, e.g. {@code created}
 * @param subject the thing itself (topic, bridge id, setting key…)
 * @param time when it happened
 * @param actor who did it: client IP address or app name
 * @param before state before, or {@code null}
 * @param after state after, or {@code null}
 * @param details other values, or {@code null}
 * @param receivedAt when Cataudit stored it
 * @param legacy {@code true} for entries copied from {@code audit_logs}
 */
@Document(collection = AuditEvent.COLLECTION)
public record AuditEvent(
        @Id String id,
        String source,
        String type,
        String app,
        String entity,
        String action,
        String subject,
        Instant time,
        String actor,
        Map<String, String> before,
        Map<String, String> after,
        Map<String, String> details,
        Instant receivedAt,
        boolean legacy) {

    /** Collection owned by Cataudit. */
    public static final String COLLECTION = "cataudit_events";
}
