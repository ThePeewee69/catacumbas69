package com.catacumbas69.catametricas.dto;

import java.time.Instant;
import java.util.List;

/**
 * Traffic and backlog over time, summed per group.
 *
 * @param source where the values come from: {@code mongodb} (later also {@code prometheus}, ADR 0013)
 * @param groupBy {@code tenant}, {@code namespace} or {@code topic}
 * @param stepSeconds length of one bucket
 * @param times start of every bucket, oldest first; every series below aligns with it
 * @param groups one per tenant, namespace or topic that has data in the range, sorted by key
 */
public record SeriesResponse(String source, String groupBy, long stepSeconds, List<Instant> times,
                             List<Group> groups) {

    /**
     * The series of one group.
     *
     * @param key tenant, namespace (without tenant) or topic (local name without partition suffix)
     * @param msgsIn messages published in each bucket
     * @param msgsOut messages delivered in each bucket
     * @param bytesIn bytes published in each bucket
     * @param bytesOut bytes delivered in each bucket
     * @param backlog messages waiting at the last sample of each bucket; {@code null} where nothing was collected
     */
    public record Group(String key, List<Long> msgsIn, List<Long> msgsOut, List<Long> bytesIn, List<Long> bytesOut,
                        List<Long> backlog) {
    }
}
