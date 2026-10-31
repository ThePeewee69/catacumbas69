package com.catacumbas69.catapulsar.service;

/**
 * Actions recorded in the audit log. The enum name is what is stored in {@code audit_logs}; the entity and
 * verb make the type of the matching event, {@code com.catacumbas69.catapulsar.<entity>.<verb>.v1}
 * (ADR 0008, orch.md §15).
 */
public enum AuditAction {
    /** A message was produced. */
    PRODUCE("message", "produced"),
    /** A topic was created. */
    CREATE_TOPIC("topic", "created"),
    /** A topic was deleted. */
    DELETE_TOPIC("topic", "deleted"),
    /** A subscription was created. */
    CREATE_SUBSCRIPTION("subscription", "created"),
    /** A subscription was deleted. */
    DELETE_SUBSCRIPTION("subscription", "deleted"),
    /** Backlog messages were peeked. */
    PEEK_MESSAGES("message", "peeked"),
    /** Messages were skipped on a subscription. */
    SKIP_MESSAGES("message", "skipped"),
    /** The whole backlog of a subscription was skipped. */
    CLEAR_BACKLOG("subscription", "backlog-cleared"),
    /** A runtime setting was changed (details: setting, from, to). */
    CHANGE_SETTING("setting", "changed"),
    /**
     * A bridge was created. Recorded in {@code audit_logs} by Cataponte since 2026-10-30 (details: bridgeId,
     * after); Catapulsar publishes {@code bridge.create-requested} events for the requests it forwards.
     */
    CREATE_BRIDGE("bridge", "create-requested"),
    /** A bridge was changed. */
    UPDATE_BRIDGE("bridge", "update-requested"),
    /** A bridge was deleted. */
    DELETE_BRIDGE("bridge", "delete-requested"),
    /** A bridge was enabled. */
    ENABLE_BRIDGE("bridge", "enable-requested"),
    /** A bridge was disabled. */
    DISABLE_BRIDGE("bridge", "disable-requested");

    private final String entity;
    private final String verb;

    AuditAction(String entity, String verb) {
        this.entity = entity;
        this.verb = verb;
    }

    /**
     * Returns what the matching event is about.
     *
     * @return entity, e.g. {@code topic}
     */
    public String entity() {
        return entity;
    }

    /**
     * Returns what happened, as used in the event type.
     *
     * @return verb, e.g. {@code created}
     */
    public String verb() {
        return verb;
    }
}
