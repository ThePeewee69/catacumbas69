package com.catacumbas69.cataponte.dto;

/**
 * Regular expressions shared by request validation (API-5, SEC-4).
 */
public final class ValidationPatterns {

    /** Fully-qualified Pulsar topic: {@code persistent://tenant/namespace/topic}. */
    public static final String TOPIC = "^(persistent|non-persistent)://[^/\\s]+/[^/\\s]+/[^/\\s]+$";

    /** Bridge and subscription names: letters, digits, dot, dash and underscore. */
    public static final String NAME = "^[A-Za-z0-9][A-Za-z0-9._-]*$";

    /** Pulsar subscription types. */
    public static final String SUBSCRIPTION_TYPE = "^(Exclusive|Shared|Failover|Key_Shared)$";

    private ValidationPatterns() {
    }
}
