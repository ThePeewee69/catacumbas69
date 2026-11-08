package com.catacumbas69.catavigia.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A closed alert, stored in {@value Schema#ARCHIVE} and kept for ever (orch.md decision 72). It keeps the open
 * alert's id, so archiving twice writes the same document.
 *
 * @param id the open alert's id ({@code _id})
 * @param key {@code <type>|<subject>}
 * @param type alert type
 * @param subject what it was about
 * @param severity {@code warning} or {@code critical}
 * @param details what the last round that saw it found
 * @param openedAt when it opened
 * @param lastSeenAt last round that saw the problem
 * @param closedAt when it closed
 * @param durationSeconds how long it was open
 * @param seenBy who marked it as seen, or {@code null}
 * @param seenAt when it was marked as seen, or {@code null}
 */
@Document(collection = Schema.ARCHIVE)
public record ArchivedAlert(
        @Id String id,
        String key,
        String type,
        String subject,
        String severity,
        Map<String, String> details,
        Instant openedAt,
        Instant lastSeenAt,
        Instant closedAt,
        long durationSeconds,
        String seenBy,
        Instant seenAt) {

    /**
     * Archives an open alert.
     *
     * @param alert the alert
     * @param closedAt now
     * @return the archived alert
     */
    public static ArchivedAlert of(Alert alert, Instant closedAt) {
        return new ArchivedAlert(alert.id(), alert.key(), alert.type(), alert.subject(), alert.severity(),
                alert.details(), alert.openedAt(), alert.lastSeenAt(), closedAt,
                Math.max(0, Duration.between(alert.openedAt(), closedAt).toSeconds()), alert.seenBy(), alert.seenAt());
    }
}
