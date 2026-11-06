package com.catacumbas69.catametricas.model;

/**
 * Names of the MongoDB collections and fields Catametricas owns (ADR 0010). Nobody else reads them: other
 * services use Catametricas' API ({@code /api/v1/topics}, {@code /api/v1/series}, {@code /api/v1/subscriptions},
 * {@code /api/v1/collector}).
 *
 * <ul>
 *   <li>{@value #SNAPSHOTS}: latest state of every topic ever seen, {@code _id} = full topic name.</li>
 *   <li>{@value #MINUTES}: traffic and backlog per topic and minute; removed after the minute retention
 *       (TTL index, 30 days by default).</li>
 *   <li>{@value #HOURS}: the same per topic and hour, kept forever.</li>
 *   <li>{@value #TOTALS}: all-time traffic per topic, {@code _id} = full topic name, kept forever.</li>
 *   <li>{@value #TASK_RUNS}: claims of collection tasks, {@code _id} = task id (ADR 0012); removed after the
 *       run retention (TTL index, 6 hours by default).</li>
 * </ul>
 *
 * <p>Traffic fields are deltas of Pulsar's counters: {@value #MSGS_IN} published, {@value #MSGS_OUT}
 * delivered (once per subscription), {@value #BYTES_IN}/{@value #BYTES_OUT} the same in bytes. Backlog
 * fields hold the last sample of the bucket. {@value #BASE_NAME} is the local topic name with any
 * {@code -partition-N} suffix removed, so partitions can be summed into their topic.
 */
public final class Schema {

    /** Latest state per topic. */
    public static final String SNAPSHOTS = "catametricas_topic_snapshots";
    /** Per-minute history. */
    public static final String MINUTES = "catametricas_topic_minutes";
    /** Per-hour history. */
    public static final String HOURS = "catametricas_topic_hours";
    /** All-time totals. */
    public static final String TOTALS = "catametricas_topic_totals";
    /** Claims of collection tasks. */
    public static final String TASK_RUNS = "catametricas_task_runs";

    /** Full topic name, e.g. {@code persistent://home/media/orders-partition-0}. */
    public static final String TOPIC = "topic";
    /** Tenant name. */
    public static final String TENANT = "tenant";
    /** Namespace name without the tenant, e.g. {@code media}. */
    public static final String NAMESPACE = "namespace";
    /** Local topic name without partition suffix, e.g. {@code orders}. */
    public static final String BASE_NAME = "baseName";
    /** Whether the topic still exists (snapshots). */
    public static final String ACTIVE = "active";
    /** When the snapshot was read (snapshots). */
    public static final String COLLECTED_AT = "collectedAt";
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
    /**
     * Subscriptions of a topic (orch.md decision 77): a list of {@link SubscriptionSnapshot} in snapshots, and in
     * minutes and hours a map keyed by {@link #subscriptionKey(String)} whose entries hold {@value #SUB_NAME},
     * {@value #SUB_TYPE}, {@value #SUB_BACKLOG} (last of the bucket), {@value #SUB_BACKLOG_MAX},
     * {@value #MSGS_OUT}, {@value #BYTES_OUT} and {@value #SUB_UNACKED} (last).
     */
    public static final String SUBS = "subs";
    /** Subscription name, inside a {@value #SUBS} entry. */
    public static final String SUB_NAME = "name";
    /** Subscription type, inside a {@value #SUBS} entry. */
    public static final String SUB_TYPE = "type";
    /** Messages waiting at the last sample of the bucket, inside a {@value #SUBS} entry. */
    public static final String SUB_BACKLOG = "backlog";
    /** Most messages waiting at any sample of the bucket, inside a {@value #SUBS} entry. */
    public static final String SUB_BACKLOG_MAX = "backlogMax";
    /** Messages delivered but not acknowledged at the last sample, inside a {@value #SUBS} entry. */
    public static final String SUB_UNACKED = "unacked";
    /** When a task was claimed (task runs; TTL). */
    public static final String CLAIMED_AT = "claimedAt";

    private Schema() {
    }

    /**
     * Turns a subscription name into a key of the {@value #SUBS} map: MongoDB field names cannot hold {@code .}
     * nor start with {@code $}, so {@code %}, {@code .} and {@code $} are escaped as {@code %25}, {@code %2E} and
     * {@code %24}.
     *
     * @param subscription subscription name
     * @return the key
     */
    public static String subscriptionKey(String subscription) {
        return subscription.replace("%", "%25").replace(".", "%2E").replace("$", "%24");
    }
}
