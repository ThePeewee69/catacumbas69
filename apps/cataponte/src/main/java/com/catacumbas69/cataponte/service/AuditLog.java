package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.AuditRecord;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.model.BridgeSource;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Records every bridge configuration change in {@code audit_logs} (OBS-6), whichever client made it.
 *
 * <p>The entry is written right after the change is stored. A failed audit write does not undo the
 * change (it is already applied); it is logged at {@code ERROR} with the full entry so it can be
 * recovered from the logs.
 */
@Slf4j
@Component
public class AuditLog {

    /** User recorded for changes Cataponte makes on its own, such as schema migration. */
    public static final String SYSTEM_USER = AuditRecord.APPLICATION;

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    /**
     * Creates the audit log.
     *
     * @param mongoTemplate template for inserts
     * @param clock time source
     */
    public AuditLog(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /**
     * Records a change of one bridge.
     *
     * @param user client IP address, or {@link #SYSTEM_USER}
     * @param action what was done
     * @param before definition before the change, or {@code null} when created
     * @param after definition after the change, or {@code null} when deleted
     */
    public void bridgeChanged(String user, AuditAction action, BridgeDocument before, BridgeDocument after) {
        BridgeDocument subject = after != null ? after : before;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("bridgeId", subject.getId());
        details.put("name", subject.getName());
        if (before != null) {
            details.put("before", snapshot(before));
        }
        if (after != null) {
            details.put("after", snapshot(after));
        }
        String topic = subject.getSource() != null ? subject.getSource().getTopic() : null;
        AuditRecord entry = new AuditRecord(clock.instant(), user, action.name(), topic, details);
        try {
            mongoTemplate.insert(entry);
        } catch (RuntimeException e) {
            log.error("Audit entry not stored: {} {} by {} details={}", action, subject.getId(), user, details, e);
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
        definition.put("version", bridge.getVersion());
        return definition;
    }
}
