package com.catacumbas69.catavigia.dto;

import com.catacumbas69.catavigia.model.Alert;
import com.catacumbas69.catavigia.model.ArchivedAlert;
import java.time.Instant;
import java.util.Map;

/**
 * An alert, open or archived.
 *
 * @param id opaque id, for {@code GET /api/v1/alerts/{id}} and {@code POST /api/v1/alerts/{id}/seen}
 * @param key {@code <type>|<subject>}
 * @param type alert type, e.g. {@code backlog}
 * @param subject what it is about
 * @param severity {@code warning} or {@code critical}
 * @param details what the last round that saw it found
 * @param openedAt when it opened
 * @param lastSeenAt last round that saw the problem
 * @param seenBy who marked it as seen, or {@code null}
 * @param seenAt when, or {@code null}
 * @param closedAt when it closed; {@code null} while open
 * @param durationSeconds how long it was open; {@code null} while open
 * @param local {@code true} for a {@code mongodb.down} kept in memory by one replica (it cannot be marked as seen)
 */
public record AlertResponse(
        String id,
        String key,
        String type,
        String subject,
        String severity,
        Map<String, String> details,
        Instant openedAt,
        Instant lastSeenAt,
        String seenBy,
        Instant seenAt,
        Instant closedAt,
        Long durationSeconds,
        boolean local) {

    /**
     * Describes an open alert.
     *
     * @param alert the alert
     * @param local whether it is kept in memory
     * @return the response
     */
    public static AlertResponse open(Alert alert, boolean local) {
        return new AlertResponse(alert.id(), alert.key(), alert.type(), alert.subject(), alert.severity(),
                alert.details(), alert.openedAt(), alert.lastSeenAt(), alert.seenBy(), alert.seenAt(), null, null,
                local);
    }

    /**
     * Describes an archived alert.
     *
     * @param alert the alert
     * @return the response
     */
    public static AlertResponse archived(ArchivedAlert alert) {
        return new AlertResponse(alert.id(), alert.key(), alert.type(), alert.subject(), alert.severity(),
                alert.details(), alert.openedAt(), alert.lastSeenAt(), alert.seenBy(), alert.seenAt(),
                alert.closedAt(), alert.durationSeconds(), false);
    }
}
