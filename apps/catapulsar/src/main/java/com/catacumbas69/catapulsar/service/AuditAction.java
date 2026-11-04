package com.catacumbas69.catapulsar.service;

/**
 * Audited actions. The entity and verb make the type of the event that records them,
 * {@code com.catacumbas69.catapulsar.<entity>.<verb>.v1} (ADR 0008, orch.md §4.4); the enum names are the
 * actions of the old {@code audit_logs}.
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
     * A bridge creation was requested through Catapulsar ({@code bridge.create-requested}); Cataponte records the
     * change itself ({@code bridge.created}).
     */
    CREATE_BRIDGE("bridge", "create-requested"),
    /** A bridge was changed. */
    UPDATE_BRIDGE("bridge", "update-requested"),
    /** A bridge was deleted. */
    DELETE_BRIDGE("bridge", "delete-requested"),
    /** A bridge was enabled. */
    ENABLE_BRIDGE("bridge", "enable-requested"),
    /** A bridge was disabled. */
    DISABLE_BRIDGE("bridge", "disable-requested"),
    /**
     * An area creation was requested through Catapulsar ({@code area.create-requested}); Cataponte records the
     * change itself ({@code area.created}, ADR 0014).
     */
    CREATE_AREA("area", "create-requested"),
    /** An area change was requested. */
    UPDATE_AREA("area", "update-requested"),
    /** An area deletion was requested (details: what to do with its bridges). */
    DELETE_AREA("area", "delete-requested");

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
