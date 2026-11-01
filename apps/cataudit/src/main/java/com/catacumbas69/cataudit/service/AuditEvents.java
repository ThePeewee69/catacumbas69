package com.catacumbas69.cataudit.service;

import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.cataudit.model.AuditEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Turns a received CloudEvent into an {@link AuditEvent}.
 */
public final class AuditEvents {

    private static final String SOURCE_PREFIX = "/catacumbas69/";
    private static final int TYPE_PARTS = 4;

    private AuditEvents() {
    }

    /**
     * Converts an event. The type {@code com.catacumbas69.<app>.<entity>.<action>.v1} gives app, entity and
     * action; a type in another shape is kept as it is, with entity {@code unknown}.
     *
     * @param event the received event
     * @param receivedAt when it was received; also used when the event has no valid time
     * @return the document, or empty if the event has no id
     */
    public static Optional<AuditEvent> from(CloudEvent event, Instant receivedAt) {
        if (event == null || event.id() == null || event.id().isBlank()) {
            return Optional.empty();
        }
        String type = event.type() == null ? "" : event.type();
        String app = appOf(event.source());
        String entity = "unknown";
        String action = type;
        if (type.startsWith(EventPublisher.TYPE_PREFIX)) {
            String[] parts = type.substring(EventPublisher.TYPE_PREFIX.length()).split("\\.");
            if (parts.length == TYPE_PARTS) {
                app = parts[0];
                entity = parts[1];
                action = parts[2];
            }
        }
        EventData data = event.data();
        return Optional.of(new AuditEvent(event.id(), event.source(), type, app, entity, action, event.subject(),
                timeOf(event.time(), receivedAt), data == null ? null : data.actor(),
                data == null ? null : data.before(), data == null ? null : data.after(),
                data == null ? null : data.details(), receivedAt, false));
    }

    private static String appOf(String source) {
        if (source != null && source.startsWith(SOURCE_PREFIX)) {
            return source.substring(SOURCE_PREFIX.length());
        }
        return "unknown";
    }

    private static Instant timeOf(String time, Instant fallback) {
        if (time == null) {
            return fallback;
        }
        try {
            return Instant.parse(time);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
