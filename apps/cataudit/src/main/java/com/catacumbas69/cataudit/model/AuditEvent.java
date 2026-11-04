package com.catacumbas69.cataudit.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One audited action, stored in {@value #COLLECTION} (ADR 0009). It is the CloudEvent as received, with the
 * parts of its type split out for querying. {@code _id} is the CloudEvent {@code id}, so a redelivered or
 * duplicated event is stored once (ADR 0012).
 *
 * @param id CloudEvent id
 * @param source CloudEvent source, {@code /catacumbas69/<app>}
 * @param type CloudEvent type, {@code com.catacumbas69.<app>.<entity>.<action>.v1}
 * @param app app that published it
 * @param entity what it is about, e.g. {@code topic}
 * @param action what happened, e.g. {@code created}
 * @param subject the thing itself (topic, bridge id, setting key…)
 * @param time when it happened
 * @param actor who did it: client IP address or app name
 * @param before state before, or {@code null}; pairs, see {@link AuditValue}
 * @param after state after, or {@code null}; pairs
 * @param details other values, or {@code null}; pairs
 * @param receivedAt when Cataudit stored it
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
        List<AuditValue> before,
        List<AuditValue> after,
        List<AuditValue> details,
        Instant receivedAt) {

    /** Collection owned by Cataudit. */
    public static final String COLLECTION = "cataudit_events";
}
