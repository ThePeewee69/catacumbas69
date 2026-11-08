package com.catacumbas69.catavigia.model;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An open alert, stored in {@value Schema#ALERTS}: at most one per {@value Schema#KEY} (unique index), so several
 * replicas never open it twice (ADR 0012). It moves to the archive when it closes.
 *
 * @param id opaque id used by the API ({@code _id})
 * @param key {@code <type>|<subject>}
 * @param type alert type ({@link AlertTypes})
 * @param subject what it is about
 * @param severity {@code warning} or {@code critical}
 * @param details what the last round that saw it found (backlog, limit, error…)
 * @param openedAt when it opened
 * @param lastSeenAt last round that saw the problem
 * @param seenBy who marked it as seen, or {@code null}
 * @param seenAt when it was marked as seen, or {@code null}
 */
@Document(collection = Schema.ALERTS)
public record Alert(
        @Id String id,
        String key,
        String type,
        String subject,
        String severity,
        Map<String, String> details,
        Instant openedAt,
        Instant lastSeenAt,
        String seenBy,
        Instant seenAt) {

    /**
     * Tells whether someone marked it as seen.
     *
     * @return {@code true} once seen; it then leaves the badge count
     */
    public boolean seen() {
        return seenAt != null;
    }
}
