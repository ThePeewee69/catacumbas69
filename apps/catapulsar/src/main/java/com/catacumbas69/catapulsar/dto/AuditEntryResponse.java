package com.catacumbas69.catapulsar.dto;

import com.catacumbas69.catapulsar.model.AuditEntry;
import java.time.Instant;
import java.util.Map;

/**
 * An audit entry as returned by the API.
 *
 * @param timestamp when the action happened
 * @param user client IP address
 * @param action action name
 * @param application {@code pulsario} (Catapulsar) or {@code passario} (Cataponte)
 * @param topic topic, or {@code null}
 * @param messageKey message key, or {@code null}
 * @param details extra details
 */
public record AuditEntryResponse(
        Instant timestamp,
        String user,
        String action,
        String application,
        String topic,
        String messageKey,
        Map<String, Object> details) {

    /**
     * Converts a stored entry. The payload is left out: it may be large and is not needed in lists.
     *
     * @param entry stored entry
     * @return the response record
     */
    public static AuditEntryResponse from(AuditEntry entry) {
        return new AuditEntryResponse(entry.getTimestamp(), entry.getUser(), entry.getAction(),
                entry.getApplication(), entry.getTopic(),
                entry.getMessageKey(), entry.getTopicProperties());
    }
}
