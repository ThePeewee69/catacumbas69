package com.catacumbas69.cataponte.service;

/**
 * Configuration changes of bridges and areas. Each change is published as
 * {@code com.catacumbas69.cataponte.<entity>.<verb>.v1} (ADR 0008) and stored by Cataudit (ADR 0009).
 */
public enum AuditAction {
    /** A bridge was created (details: after). */
    CREATE_BRIDGE(Entities.BRIDGE, "created"),
    /** A bridge definition was changed (details: before, after). */
    UPDATE_BRIDGE(Entities.BRIDGE, "updated"),
    /** A bridge was deleted (details: before). */
    DELETE_BRIDGE(Entities.BRIDGE, "deleted"),
    /** A bridge was enabled. */
    ENABLE_BRIDGE(Entities.BRIDGE, "enabled"),
    /** A bridge was disabled. */
    DISABLE_BRIDGE(Entities.BRIDGE, "disabled"),
    /** An area was created (ADR 0014). */
    CREATE_AREA(Entities.AREA, "created"),
    /** An area was changed. */
    UPDATE_AREA(Entities.AREA, "updated"),
    /** An area was deleted. */
    DELETE_AREA(Entities.AREA, "deleted");

    private final String entity;
    private final String verb;

    AuditAction(String entity, String verb) {
        this.entity = entity;
        this.verb = verb;
    }

    /**
     * Returns what was changed, as used in the event type.
     *
     * @return entity, {@code bridge} or {@code area}
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

    /** Entities of the events Cataponte publishes. */
    private static final class Entities {
        static final String BRIDGE = "bridge";
        static final String AREA = "area";

        private Entities() {
        }
    }
}
