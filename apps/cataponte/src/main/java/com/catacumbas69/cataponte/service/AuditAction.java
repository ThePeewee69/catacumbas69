package com.catacumbas69.cataponte.service;

/**
 * Configuration changes recorded in {@code audit_logs}. Names match Catapulsar's {@code AuditAction}. Each
 * change is also published as {@code com.catacumbas69.cataponte.bridge.<verb>.v1} (ADR 0008).
 */
public enum AuditAction {
    /** A bridge was created (details: after). */
    CREATE_BRIDGE("created"),
    /** A bridge definition was changed (details: before, after). */
    UPDATE_BRIDGE("updated"),
    /** A bridge was deleted (details: before). */
    DELETE_BRIDGE("deleted"),
    /** A bridge was enabled. */
    ENABLE_BRIDGE("enabled"),
    /** A bridge was disabled. */
    DISABLE_BRIDGE("disabled"),
    /** A stored bridge was rewritten to the current schema at start-up (details: before, after). */
    MIGRATE_BRIDGE("migrated");

    /** Entity of every event Cataponte publishes for these actions. */
    public static final String ENTITY = "bridge";

    private final String verb;

    AuditAction(String verb) {
        this.verb = verb;
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
