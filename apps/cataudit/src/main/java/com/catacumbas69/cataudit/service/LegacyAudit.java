package com.catacumbas69.cataudit.service;

import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catacommons.events.EventValues;
import com.catacumbas69.cataudit.model.AuditEvent;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Converts an entry of the old shared {@code audit_logs} collection (ADR 0006) into an {@link AuditEvent},
 * with the type its action would have as an event today (orch.md §4.4). The id is {@code legacy-<ObjectId>},
 * so copying again changes nothing.
 */
public final class LegacyAudit {

    /** Prefix of the ids of copied entries. */
    public static final String ID_PREFIX = "legacy-";

    private static final Map<String, String[]> ACTIONS = Map.ofEntries(
            Map.entry("PRODUCE", new String[] {"message", "produced"}),
            Map.entry("CREATE_TOPIC", new String[] {"topic", "created"}),
            Map.entry("DELETE_TOPIC", new String[] {"topic", "deleted"}),
            Map.entry("CREATE_SUBSCRIPTION", new String[] {"subscription", "created"}),
            Map.entry("DELETE_SUBSCRIPTION", new String[] {"subscription", "deleted"}),
            Map.entry("PEEK_MESSAGES", new String[] {"message", "peeked"}),
            Map.entry("SKIP_MESSAGES", new String[] {"message", "skipped"}),
            Map.entry("CLEAR_BACKLOG", new String[] {"subscription", "backlog-cleared"}),
            Map.entry("CHANGE_SETTING", new String[] {"setting", "changed"}),
            Map.entry("CREATE_BRIDGE", new String[] {"bridge", "created"}),
            Map.entry("UPDATE_BRIDGE", new String[] {"bridge", "updated"}),
            Map.entry("DELETE_BRIDGE", new String[] {"bridge", "deleted"}),
            Map.entry("ENABLE_BRIDGE", new String[] {"bridge", "enabled"}),
            Map.entry("DISABLE_BRIDGE", new String[] {"bridge", "disabled"}),
            Map.entry("MIGRATE_BRIDGE", new String[] {"bridge", "migrated"}));

    /** Old {@code application} values → current app names. Entries without one were written by Pulsário. */
    private static final Map<String, String> APPS = Map.of("pulsario", "catapulsar", "passario", "cataponte");

    private LegacyAudit() {
    }

    /**
     * Converts one {@code audit_logs} document.
     *
     * @param entry the stored entry
     * @param receivedAt when it is copied
     * @return the event, or empty if the entry has no {@code _id}
     */
    public static Optional<AuditEvent> toEvent(Document entry, Instant receivedAt) {
        Object rawId = entry.get("_id");
        if (rawId == null) {
            return Optional.empty();
        }
        String id = ID_PREFIX + (rawId instanceof ObjectId objectId ? objectId.toHexString() : rawId.toString());
        String application = entry.getString("application");
        String app = application == null ? "catapulsar" : APPS.getOrDefault(application, "catapulsar");
        String oldAction = entry.getString("action") == null ? "UNKNOWN" : entry.getString("action");
        String[] mapped = ACTIONS.getOrDefault(oldAction,
                new String[] {"legacy", oldAction.toLowerCase(Locale.ROOT).replace('_', '-')});
        Map<String, Object> properties = new LinkedHashMap<>();
        Object stored = entry.get("topicProperties");
        if (stored instanceof Map<?, ?> map) {
            map.forEach((key, value) -> properties.put(String.valueOf(key), value));
        }
        String subject = subjectOf(mapped[0], entry.getString("topic"), properties);
        Map<String, Object> before = asMap(properties.remove("before"));
        Map<String, Object> after = asMap(properties.remove("after"));
        if ("setting".equals(mapped[0])) {
            before = valueMap(properties.remove("from"));
            after = valueMap(properties.remove("to"));
        }
        putIfPresent(properties, "topic", entry.getString("topic"));
        putIfPresent(properties, "key", entry.getString("messageKey"));
        putIfPresent(properties, "payload", entry.getString("messagePayload"));
        return Optional.of(new AuditEvent(id, "/catacumbas69/" + app,
                EventPublisher.type(app, mapped[0], mapped[1]), app, mapped[0], mapped[1], subject,
                timeOf(entry.get("timestamp"), receivedAt), entry.getString("user"),
                EventValues.flatten(before), EventValues.flatten(after), EventValues.flatten(properties),
                receivedAt, true));
    }

    private static String subjectOf(String entity, String topic, Map<String, Object> properties) {
        Object key = switch (entity) {
            case "setting" -> properties.get("setting");
            case "bridge" -> properties.get("bridgeId");
            default -> null;
        };
        return key != null ? String.valueOf(key) : topic;
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static Map<String, Object> valueMap(Object value) {
        return value == null ? null : Map.of("value", value);
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null) {
            values.putIfAbsent(key, value);
        }
    }

    private static Instant timeOf(Object value, Instant fallback) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return fallback;
    }
}
