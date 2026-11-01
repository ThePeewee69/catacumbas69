package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Records every audited action by publishing it as an event on {@code catacumbas69/catapulsar/events}
 * (ADR 0008). The {@code audit-catapulsar} bridge carries it to Cataudit, which owns the audit trail
 * (ADR 0009); the audit view reads it back from Cataudit through {@link AuditProxyService}.
 *
 * <p>Publishing is asynchronous and best-effort (RES-11): the user's action never waits for Pulsar and never
 * fails because auditing failed; an event that cannot be published is logged at {@code ERROR} in full.
 */
@Service
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final EventPublisher events;

    /**
     * Creates the service.
     *
     * @param events publishes the events
     */
    public AuditService(EventPublisher events) {
        this.events = events;
    }

    /**
     * Records an action without blocking the caller.
     *
     * @param user client IP address
     * @param action what happened
     * @param topic topic involved, or {@code null}
     * @param details extra details, or {@code null}
     */
    public void record(String user, AuditAction action, String topic, Map<String, Object> details) {
        recordMessage(user, action, topic, null, null, details);
    }

    /**
     * Records an action that involves a message payload, without blocking the caller.
     *
     * @param user client IP address
     * @param action what happened
     * @param topic topic involved
     * @param payload message payload, or {@code null}
     * @param key message key, or {@code null}
     * @param details extra details, or {@code null}
     */
    public void recordMessage(String user, AuditAction action, String topic, String payload, String key,
                              Map<String, Object> details) {
        try {
            if (action == AuditAction.CHANGE_SETTING && details != null) {
                publishSettingChanged(user, details);
                return;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            if (details != null) {
                values.putAll(details);
            }
            if (key != null) {
                values.put("key", key);
            }
            if (payload != null) {
                values.put("payload", payload);
            }
            events.publish(action.entity(), action.verb(), topic, EventData.action(user, values));
        } catch (RuntimeException e) {
            LOG.warn("Event not published: action={} topic={}: {}", action, topic, e.getMessage());
        }
    }

    /** Subject is the setting id (its key on the settings topic); before/after hold {@code value}. */
    private void publishSettingChanged(String user, Map<String, Object> details) {
        String id = String.valueOf(details.get("setting"));
        events.publish(AuditAction.CHANGE_SETTING.entity(), AuditAction.CHANGE_SETTING.verb(),
                id, EventData.change(user, value(details.get("from")), value(details.get("to")), null));
    }

    private static Map<String, Object> value(Object value) {
        return value == null ? null : Map.of("value", value);
    }
}
