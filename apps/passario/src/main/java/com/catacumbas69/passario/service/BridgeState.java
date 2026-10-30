package com.catacumbas69.passario.service;

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
    FAILED
}
