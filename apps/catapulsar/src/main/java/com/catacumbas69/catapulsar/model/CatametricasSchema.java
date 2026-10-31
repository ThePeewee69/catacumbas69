package com.catacumbas69.catapulsar.model;

/**
 * Names of the MongoDB collections and fields written by Catametricas, which Catapulsar reads for the
 * overviews (ADR 0007). Mirror of {@code com.catacumbas69.catametricas.model.Schema}: change both together.
 */
public final class CatametricasSchema {

    /** Latest state per topic. */
    public static final String SNAPSHOTS = "contario_topic_snapshots";
    /** Per-minute history (30 days). */
    public static final String MINUTES = "contario_topic_minutes";
    /** Per-hour history (forever). */
    public static final String HOURS = "contario_topic_hours";
    /** All-time totals per topic. */
    public static final String TOTALS = "contario_topic_totals";
    /** Last collection run. */
    public static final String STATUS = "contario_status";
    /** {@code _id} of the status document. */
    public static final String STATUS_ID = "collector";

    /** Tenant name. */
    public static final String TENANT = "tenant";
    /** Namespace name without the tenant. */
    public static final String NAMESPACE = "namespace";
    /** Local topic name without partition suffix. */
    public static final String BASE_NAME = "baseName";
    /** Whether the topic still exists (snapshots). */
    public static final String ACTIVE = "active";
    /** When the snapshot was read. */
    public static final String COLLECTED_AT = "collectedAt";
    /** Start of a minute bucket. */
    public static final String MINUTE = "minute";
    /** Start of an hour bucket. */
    public static final String HOUR = "hour";
    /** Messages published. */
    public static final String MSGS_IN = "msgsIn";
    /** Messages delivered. */
    public static final String MSGS_OUT = "msgsOut";
    /** Bytes published. */
    public static final String BYTES_IN = "bytesIn";
    /** Bytes delivered. */
    public static final String BYTES_OUT = "bytesOut";
    /** Messages waiting. */
    public static final String BACKLOG_MSGS = "backlogMsgs";
    /** Bytes waiting. */
    public static final String BACKLOG_BYTES = "backlogBytes";

    private CatametricasSchema() {
    }
}
