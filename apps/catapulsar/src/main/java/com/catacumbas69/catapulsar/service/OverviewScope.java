package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.service.CatametricasClient.TopicStats;
import java.util.Locale;
import java.util.function.Function;

/**
 * What an overview shows: the cluster (rows = tenants), a tenant (rows = namespaces) or a namespace (rows =
 * topics, partitions summed).
 */
public enum OverviewScope {

    /** Whole cluster, one row per tenant. */
    CLUSTER("tenant", TopicStats::tenant),
    /** One tenant, one row per namespace. */
    TENANT("namespace", TopicStats::namespace),
    /** One namespace, one row per topic. */
    NAMESPACE("topic", TopicStats::baseName);

    private final String groupBy;
    private final Function<TopicStats, String> row;

    OverviewScope(String groupBy, Function<TopicStats, String> row) {
        this.groupBy = groupBy;
        this.row = row;
    }

    /**
     * Returns the scope as the API names it.
     *
     * @return {@code cluster}, {@code tenant} or {@code namespace}
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns what a row is, as Catametricas' {@code groupBy} parameter.
     *
     * @return {@code tenant}, {@code namespace} or {@code topic}
     */
    public String groupBy() {
        return groupBy;
    }

    /**
     * Returns the row a topic belongs to.
     *
     * @param topic a topic or partition
     * @return its tenant, namespace or local name without partition suffix
     */
    public String rowOf(TopicStats topic) {
        return row.apply(topic);
    }
}
