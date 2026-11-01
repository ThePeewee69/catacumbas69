package com.catacumbas69.cataudit.dto;

import com.catacumbas69.cataudit.model.AuditEvent;
import java.time.Instant;
import java.util.Map;

/**
 * One audit entry as returned by the API.
 *
 * @param id event id
 * @param time when it happened
 * @param app app that published it
 * @param entity what it is about
 * @param action what happened
 * @param type full CloudEvent type
 * @param subject the thing itself
 * @param actor who did it
 * @param before state before, or {@code null}
 * @param after state after, or {@code null}
 * @param details other values, or {@code null}
 */
public record AuditEventResponse(String id, Instant time, String app, String entity, String action, String type,
                                 String subject, String actor, Map<String, String> before,
                                 Map<String, String> after, Map<String, String> details) {

    /**
     * Converts a stored event.
     *
     * @param event the stored event
     * @return the response
     */
    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(event.id(), event.time(), event.app(), event.entity(), event.action(),
                event.type(), event.subject(), event.actor(), event.before(), event.after(), event.details());
    }
}
