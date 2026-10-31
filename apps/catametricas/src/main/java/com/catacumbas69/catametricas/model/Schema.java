package com.catacumbas69.catametricas.model;

/**
 * Names of the MongoDB collections and fields Catametricas writes. Catapulsar reads the same collections
 * (ADR 0007), so a change here is a change of a shared contract (MONGO-2).
 *
 * <ul>
 *   <li>{@value #SNAPSHOTS} — latest state of every topic ever seen, {@code _id} = full topic name.</li>
 *   <li>{@value #MINUTES} — traffic and backlog per topic and minute; removed after the minute retention
 *       (TTL index, 30 days by default).</li>
 *   <li>{@value #HOURS} — the same per topic and hour, kept forever.</li>
 *   <li>{@value #TOTALS} — all-time traffic per topic, {@code _id} = full topic name, kept forever.</li>
 *   <li>{@value #STATUS} — the last collection run, {@code _id} = {@value #STATUS_ID}.</li>
 *   <li>{@value #LOCKS} — the collector lease, {@code _id} = {@value #LOCK_ID}.</li>
 * </ul>
 *
 * <p>Traffic fields are deltas of Pulsar's counters: {@value #MSGS_IN} published, {@value #MSGS_OUT}
 * delivered (once per subscription), {@value #BYTES_IN}/{@value #BYTES_OUT} the same in bytes. Backlog
 * fields hold the last sample of the bucket. {@value #BASE_NAME} is the local topic name with any
 * {@code -partition-N} suffix removed, so partitions can be summed into their topic.
 */
public final class Schema {

    /** Latest state per topic. */
    public static final String SNAPSHOTS = "contario_topic_snapshots";
    /** Per-minute history. */
    public static final String MINUTES = "contario_topic_minutes";
    /** Per-hour history. */
    public static final String HOURS = "contario_topic_hours";
    /** All-time totals. */
    public static final String TOTALS = "contario_topic_totals";
    /** Last collection run. */
    public static final String STATUS = "contario_status";
    /** Collector lease. */
    public static final String LOCKS = "contario_locks";

    /** {@code _id} of the status document. */
    public static final String STATUS_ID = "collector";
    /** {@code _id} of the collector lease. */
    public static final String LOCK_ID = "collector";

    /** Full topic name, e.g. {@code persistent://home/media/orders-partition-0}. */
    public static final String TOPIC = "topic";
    /** Tenant name. */
    public static final String TENANT = "tenant";
    /** Namespace name without the tenant, e.g. {@code media}. */
    public static final String NAMESPACE = "namespace";
    /** Local topic name without partition suffix, e.g. {@code orders}. */
    public static final String BASE_NAME = "baseName";
    /** Start of the minute bucket (UTC). */
    public static final String MINUTE = "minute";
    /** Start of the hour bucket (UTC). */
    public static final String HOUR = "hour";
    /** Messages published. */
    public static final String MSGS_IN = "msgsIn";
    /** Messages delivered to consumers. */
    public static final String MSGS_OUT = "msgsOut";
    /** Bytes published. */
    public static final String BYTES_IN = "bytesIn";
    /** Bytes delivered. */
    public static final String BYTES_OUT = "bytesOut";
    /** Messages waiting in subscriptions (sum over subscriptions). */
    public static final String BACKLOG_MSGS = "backlogMsgs";
    /** Bytes waiting in subscriptions. */
    public static final String BACKLOG_BYTES = "backlogBytes";
    /** First time the topic was seen (totals). */
    public static final String FIRST_SEEN_AT = "firstSeenAt";
    /** Last time the topic was seen (totals). */
    public static final String LAST_SEEN_AT = "lastSeenAt";

    private Schema() {
    }
}
