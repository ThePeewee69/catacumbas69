package com.catacumbas69.cataponte.service;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.model.BridgeSource;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Records every bridge configuration change, whichever client made it (OBS-6), by publishing a
 * {@code bridge.<verb>} event on {@code catacumbas69/cataponte/events} (ADR 0008). The {@code audit-cataponte}
 * bridge carries it to Cataudit, which owns the audit trail (ADR 0009).
 *
 * <p>The event is published right after the change is stored, asynchronously and with retries; it never
 * fails the change. Subject = bridge id; {@code before}/{@code after} = the definitions, flattened.
 */
@Slf4j
@Component
public class AuditLog {

    /** Actor for changes Cataponte makes on its own, such as registering its system bridge. */
    public static final String SYSTEM_USER = "cataponte";

    private final EventPublisher events;

    /**
     * Creates the audit log.
     *
     * @param events publishes the events
     */
    public AuditLog(EventPublisher events) {
        this.events = events;
    }

    /**
     * Records a change of one bridge.
     *
     * @param user client IP address, the calling app's name, or {@link #SYSTEM_USER}
     * @param action what was done
     * @param before definition before the change, or {@code null} when created
     * @param after definition after the change, or {@code null} when deleted
     */
    public void bridgeChanged(String user, AuditAction action, BridgeDocument before, BridgeDocument after) {
        BridgeDocument subject = after != null ? after : before;
        try {
            events.publish(AuditAction.ENTITY, action.verb(), subject.getId(), EventData.change(user,
                    before == null ? null : snapshot(before), after == null ? null : snapshot(after), null));
        } catch (RuntimeException e) {
            log.warn("Event not published: {} {}: {}", action, subject.getId(), e.getMessage());
        }
    }

    /**
     * Copies the user-visible definition of a bridge; later changes to {@code bridge} do not affect it.
     *
     * @param bridge the bridge
     * @return its definition as plain values
     */
    static Map<String, Object> snapshot(BridgeDocument bridge) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", bridge.getName());
        definition.put("description", bridge.getDescription());
        definition.put("enabled", bridge.isEnabled());
        BridgeSource source = bridge.getSource();
        if (source != null) {
            Map<String, Object> sourceValues = new LinkedHashMap<>();
            sourceValues.put("topic", source.getTopic());
            sourceValues.put("subscription", source.getSubscription());
            sourceValues.put("subscriptionType", source.subscriptionTypeOrDefault());
            definition.put("source", sourceValues);
        }
        definition.put("destinations", bridge.resolveDestinationTopics());
        definition.put("ordered", bridge.isOrderedEffective());
        definition.put("maxInFlight", bridge.getMaxInFlight());
        definition.put("preserveKey", bridge.isPreserveKey());
        definition.put("preserveProperties", bridge.isPreserveProperties());
        definition.put("system", bridge.isSystem());
        definition.put("version", bridge.getVersion());
        return definition;
    }
}
