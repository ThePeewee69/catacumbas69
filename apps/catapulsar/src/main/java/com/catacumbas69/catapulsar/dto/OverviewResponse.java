package com.catacumbas69.catapulsar.dto;

import java.time.Instant;
import java.util.List;

/**
 * An overview of the cluster (rows = tenants), a tenant (rows = namespaces) or a namespace (rows =
 * topics, partitions summed), built from Catametricas' statistics.
 *
 * @param scope {@code cluster}, {@code tenant} or {@code namespace}
 * @param tenant tenant of the scope, or {@code null}
 * @param namespace namespace of the scope, or {@code null}
 * @param period {@code 1h}, {@code 24h}, {@code 7d} or {@code 30d}
 * @param stepSeconds length of one chart bucket
 * @param times start of every chart bucket, oldest first; series values align with it
 * @param collectedAt end of Catametricas' last collection, or {@code null} if it never ran
 * @param intervalSeconds Catametricas' interval at its last collection, or {@code null}
 * @param summary the whole scope
 * @param rows one per tenant, namespace or topic, sorted by name
 */
public record OverviewResponse(
        String scope,
        String tenant,
        String namespace,
        String period,
        long stepSeconds,
        List<Instant> times,
        Instant collectedAt,
        Integer intervalSeconds,
        Row summary,
        List<Row> rows) {

    /**
     * Statistics of one tenant, namespace or topic.
     *
     * @param name tenant, namespace or topic name ({@code null} for the summary)
     * @param current state at the last collection, or {@code null} if nothing in it exists any more
     * @param period traffic in the selected period
     * @param total traffic since Catametricas started collecting
     * @param series chart values per bucket
     */
    public record Row(String name, Current current, Traffic period, Traffic total, Series series) {
    }

    /**
     * State at the last collection, summed over the topics of the row.
     *
     * @param topics topics (partitioned topics count once)
     * @param partitions topic partitions, or topics if not partitioned
     * @param backlogMsgs messages waiting
     * @param backlogBytes bytes waiting
     * @param msgRateIn published messages per second
     * @param msgRateOut delivered messages per second
     * @param throughputIn published bytes per second
     * @param throughputOut delivered bytes per second
     * @param storageSize bytes stored
     * @param subscriptions subscriptions
     * @param producers producers
     * @param consumers consumers
     */
    public record Current(
            int topics,
            int partitions,
            long backlogMsgs,
            long backlogBytes,
            double msgRateIn,
            double msgRateOut,
            double throughputIn,
            double throughputOut,
            long storageSize,
            int subscriptions,
            int producers,
            int consumers) {
    }

    /**
     * Messages and bytes in and out.
     *
     * @param msgsIn messages published
     * @param msgsOut messages delivered
     * @param bytesIn bytes published
     * @param bytesOut bytes delivered
     */
    public record Traffic(long msgsIn, long msgsOut, long bytesIn, long bytesOut) {

        /** No traffic. */
        public static final Traffic NONE = new Traffic(0, 0, 0, 0);

        /**
         * Adds two traffic values.
         *
         * @param other the other value
         * @return the sum
         */
        public Traffic plus(Traffic other) {
            return new Traffic(msgsIn + other.msgsIn, msgsOut + other.msgsOut, bytesIn + other.bytesIn,
                    bytesOut + other.bytesOut);
        }
    }

    /**
     * Chart values per bucket, aligned with {@code times}.
     *
     * @param msgsIn messages published in each bucket
     * @param msgsOut messages delivered in each bucket
     * @param backlog backlog at the end of each bucket; {@code null} where nothing was collected
     */
    public record Series(List<Long> msgsIn, List<Long> msgsOut, List<Long> backlog) {
    }
}
