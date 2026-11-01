package com.catacumbas69.cataponte.dto;

/**
 * Regular expressions shared by request validation (API-5, SEC-4).
 */
public final class ValidationPatterns {

    /** Fully-qualified Pulsar topic: {@code persistent://tenant/namespace/topic}. */
    public static final String TOPIC = "^(persistent|non-persistent)://[^/\\s]+/[^/\\s]+/[^/\\s]+$";

    /** Bridge and subscription names: letters, digits, dot, dash and underscore. */
    public static final String NAME = "^[A-Za-z0-9][A-Za-z0-9._-]*$";

    /**
     * Pulsar subscription types a bridge may use. {@code Exclusive} is not one: every Cataponte replica runs
     * every bridge, and only one consumer may join an exclusive subscription (ADR 0012).
     */
    public static final String SUBSCRIPTION_TYPE = "^(Shared|Failover|Key_Shared)$";

    private ValidationPatterns() {
    }
}
