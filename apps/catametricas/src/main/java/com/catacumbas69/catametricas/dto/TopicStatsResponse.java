package com.catacumbas69.catametricas.dto;

import java.time.Instant;

/**
 * Statistics of one topic (or partition): its state at the last collection and its all-time traffic.
 *
 * @param topic full topic name, e.g. {@code persistent://home/media/orders-partition-0}
 * @param tenant tenant name
 * @param namespace namespace name without the tenant
 * @param name local topic name, e.g. {@code orders-partition-0}
 * @param baseName local name without partition suffix, e.g. {@code orders}
 * @param current state at the last collection, or {@code null} if the topic no longer exists
 * @param total traffic since Catametricas first saw the topic, or {@code null} if none was counted yet
 */
public record TopicStatsResponse(
        String topic,
        String tenant,
        String namespace,
        String name,
        String baseName,
        Current current,
        Traffic total) {

    /**
     * State at the last collection.
     *
     * @param collectedAt when it was read
     * @param backlogMsgs messages waiting, summed over subscriptions
     * @param backlogBytes bytes waiting
     * @param msgRateIn published messages per second
     * @param msgRateOut delivered messages per second
     * @param throughputIn published bytes per second
     * @param throughputOut delivered bytes per second
     * @param storageSize bytes stored
     * @param subscriptions subscriptions
     * @param producers connected producers
     * @param consumers connected consumers, over all subscriptions
     */
    public record Current(
            Instant collectedAt,
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
     * @param msgsOut messages delivered (once per subscription)
     * @param bytesIn bytes published
     * @param bytesOut bytes delivered
     */
    public record Traffic(long msgsIn, long msgsOut, long bytesIn, long bytesOut) {
    }
}
