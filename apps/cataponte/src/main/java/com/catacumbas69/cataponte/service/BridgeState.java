package com.catacumbas69.cataponte.service;

/**
 * Lifecycle state of a bridge worker.
 */
public enum BridgeState {
    /** Producers and consumer are being created. */
    STARTING,
    /** Relaying messages. */
    RUNNING,
    /** Draining in-flight messages and closing resources. */
    STOPPING,
    /** Fully stopped. */
    STOPPED,
    /** Could not start; the manager retries with back-off. */
    FAILED,
    /** Not started because it is part of a message loop (ADR 0005). */
    BLOCKED
}
