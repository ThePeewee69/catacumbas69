package com.catacumbas69.catavigia.model;

import java.util.Map;

/**
 * Alert types, their severity and the check that reports them (ADR 0015).
 */
public final class AlertTypes {

    /** Severity of a problem someone should look at. */
    public static final String WARNING = "warning";
    /** Severity of something down. */
    public static final String CRITICAL = "critical";

    /** A DLQ has messages waiting on {@code dlq}; subject = the DLQ topic. */
    public static final String POISON = "poison";
    /** A bridge is {@code FAILED}; subject = the bridge's name. */
    public static final String BRIDGE_FAILED = "bridge.failed";
    /** A bridge is {@code BLOCKED} (loop); subject = the bridge's name. */
    public static final String BRIDGE_BLOCKED = "bridge.blocked";
    /** Catametricas has not collected for too long; subject = {@code catametricas}. */
    public static final String COLLECTION_STALE = "collection.stale";
    /** A subscription's backlog is above its limit; subject = {@code <topic>|<subscription>}. */
    public static final String BACKLOG = "backlog";
    /** A consumer of ours is paused on some replica; subject = {@code <app>/<consumer>}. */
    public static final String CONSUMER_PAUSED = "consumer.paused";
    /** An app's readiness is not {@code UP}; subject = the app. */
    public static final String SERVICE_DOWN = "service.down";
    /** Fewer brokers active than expected; subject = {@code brokers}. */
    public static final String PULSAR_BROKER_DOWN = "pulsar.broker.down";
    /** Brokers active but the broker health check fails (storage); subject = {@code healthcheck}. */
    public static final String PULSAR_BOOKIE_DOWN = "pulsar.bookie.down";
    /** This replica's MongoDB does not answer; kept in memory; subject = the replica (orch.md decision 76). */
    public static final String MONGODB_DOWN = "mongodb.down";

    private static final Map<String, String> SEVERITY = Map.of(
            SERVICE_DOWN, CRITICAL,
            PULSAR_BROKER_DOWN, CRITICAL,
            PULSAR_BOOKIE_DOWN, CRITICAL,
            MONGODB_DOWN, CRITICAL);

    private AlertTypes() {
    }

    /**
     * Returns the severity of a type.
     *
     * @param type alert type
     * @return {@value #CRITICAL} for something down, otherwise {@value #WARNING}
     */
    public static String severity(String type) {
        return SEVERITY.getOrDefault(type, WARNING);
    }
}
