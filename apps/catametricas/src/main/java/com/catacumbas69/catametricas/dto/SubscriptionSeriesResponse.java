package com.catacumbas69.catametricas.dto;

import java.time.Instant;
import java.util.List;

/**
 * History of one subscription, its partitions summed (orch.md decision 77). Every list aligns with
 * {@code times}; backlog, its maximum and unacked are {@code null} where nothing was collected.
 *
 * @param source where the values come from: {@code mongodb}
 * @param tenant tenant name
 * @param namespace namespace name without the tenant
 * @param topic local topic name without partition suffix
 * @param subscription subscription name
 * @param stepSeconds length of one bucket
 * @param times start of every bucket, oldest first
 * @param backlog messages waiting at the last sample of each bucket
 * @param backlogMax most messages waiting at any sample of each bucket
 * @param msgsOut messages delivered in each bucket
 * @param bytesOut bytes delivered in each bucket
 * @param unacked messages not acknowledged at the last sample of each bucket
 */
public record SubscriptionSeriesResponse(
        String source,
        String tenant,
        String namespace,
        String topic,
        String subscription,
        long stepSeconds,
        List<Instant> times,
        List<Long> backlog,
        List<Long> backlogMax,
        List<Long> msgsOut,
        List<Long> bytesOut,
        List<Long> unacked) {
}
