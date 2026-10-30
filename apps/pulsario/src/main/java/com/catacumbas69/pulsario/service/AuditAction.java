package com.catacumbas69.pulsario.service;

/**
 * Actions recorded in the audit log. The enum name is what is stored.
 */
public enum AuditAction {
    /** A message was produced. */
    PRODUCE,
    /** A topic was created. */
    CREATE_TOPIC,
    /** A topic was deleted. */
    DELETE_TOPIC,
    /** A subscription was created. */
    CREATE_SUBSCRIPTION,
    /** A subscription was deleted. */
    DELETE_SUBSCRIPTION,
    /** Backlog messages were peeked. */
    PEEK_MESSAGES,
    /** Messages were skipped on a subscription. */
    SKIP_MESSAGES,
    /** A bridge was created. */
    CREATE_BRIDGE,
    /** A bridge was changed. */
    UPDATE_BRIDGE,
    /** A bridge was deleted. */
    DELETE_BRIDGE,
    /** A bridge was enabled. */
    ENABLE_BRIDGE,
    /** A bridge was disabled. */
    DISABLE_BRIDGE
}
