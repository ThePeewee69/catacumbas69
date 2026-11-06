package com.catacumbas69.catametricas.dto;

import java.time.Instant;

/**
 * One subscription of a topic or partition at the last collection (orch.md decision 77).
 *
 * @param topic full topic or partition name
 * @param tenant tenant name
 * @param namespace namespace name without the tenant
 * @param name local topic name, e.g. {@code orders-partition-0}
 * @param baseName local name without partition suffix, e.g. {@code orders}
 * @param subscription subscription name
 * @param type {@code Shared}, {@code Failover}, {@code Key_Shared} or {@code Exclusive}
 * @param collectedAt when it was read
 * @param backlog messages waiting
 * @param unacked messages delivered but not acknowledged yet
 * @param consumers connected consumers
 * @param msgRateOut delivered messages per second
 */
public record SubscriptionStatsResponse(
        String topic,
        String tenant,
        String namespace,
        String name,
        String baseName,
        String subscription,
        String type,
        Instant collectedAt,
        long backlog,
        long unacked,
        int consumers,
        double msgRateOut) {
}
