package com.catacumbas69.catacommons.audit;

import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;

/**
 * A bridge an app needs in Cataponte, registered by the app itself at start-up and marked as a system
 * bridge (ADR 0009).
 *
 * @param name unique bridge name, e.g. {@code audit-catapulsar}
 * @param description shown in the Bridges panel
 * @param sourceTopic topic read
 * @param subscription subscription name on the source topic
 * @param destinationTopic topic written
 */
public record SystemBridge(String name, String description, String sourceTopic, String subscription,
                           String destinationTopic) {

    /** Prefix of every audit bridge name. */
    public static final String AUDIT_PREFIX = "audit-";

    /**
     * The bridge that feeds an app's events to Cataudit: {@code <app>/events} → {@code cataudit/audit}.
     *
     * @param app app name
     * @return the bridge {@code audit-<app>}
     */
    public static SystemBridge audit(String app) {
        return new SystemBridge(AUDIT_PREFIX + app, "Audit trail of " + app + " → Cataudit (system bridge)",
                CatacumbasTopics.events(app), "cataponte-" + AUDIT_PREFIX + app, CatacumbasTopics.audit());
    }
}
