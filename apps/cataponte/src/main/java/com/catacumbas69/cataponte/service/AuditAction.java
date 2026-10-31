package com.catacumbas69.cataponte.service;

/**
 * Configuration changes recorded in {@code audit_logs}. Names match Catapulsar's {@code AuditAction}.
 */
public enum AuditAction {
    /** A bridge was created (details: after). */
    CREATE_BRIDGE,
    /** A bridge definition was changed (details: before, after). */
    UPDATE_BRIDGE,
    /** A bridge was deleted (details: before). */
    DELETE_BRIDGE,
    /** A bridge was enabled. */
    ENABLE_BRIDGE,
    /** A bridge was disabled. */
    DISABLE_BRIDGE,
    /** A stored bridge was rewritten to the current schema at start-up (details: before, after). */
    MIGRATE_BRIDGE
}
