package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.dto.SeriesResponse.Group;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Places history (already summed per group and minute or hour by MongoDB) into buckets: traffic is added up,
 * backlog is the value at the last minute or hour of the bucket that has data. Pure logic, no I/O.
 */
public final class SeriesAssembler {

    private final List<Instant> times;
    private final long stepMillis;
    private final Map<String, GroupBuilder> groups = new TreeMap<>();

    /**
     * Creates an assembler.
     *
     * @param times bucket starts, oldest first
     * @param step bucket length
     */
    public SeriesAssembler(List<Instant> times, Duration step) {
        this.times = times;
        this.stepMillis = step.toMillis();
    }

    /**
     * Adds one group's history at one minute or hour.
     *
     * @param key group key ({@code null} becomes {@code ?})
     * @param time minute or hour
     * @param msgsIn messages published
     * @param msgsOut messages delivered
     * @param bytesIn bytes published
     * @param bytesOut bytes delivered
     * @param backlog messages waiting at that time
     * @return this assembler
     */
    public SeriesAssembler add(String key, Instant time, long msgsIn, long msgsOut, long bytesIn, long bytesOut,
                               long backlog) {
        int bucket = bucketOf(time);
        if (bucket < 0) {
            return this;
        }
        GroupBuilder group = groups.computeIfAbsent(key == null ? "?" : key, name -> new GroupBuilder(times.size()));
        group.msgsIn[bucket] += msgsIn;
        group.msgsOut[bucket] += msgsOut;
        group.bytesIn[bucket] += bytesIn;
        group.bytesOut[bucket] += bytesOut;
        if (group.backlogAt[bucket] == null || !time.isBefore(group.backlogAt[bucket])) {
            group.backlogAt[bucket] = time;
            group.backlog[bucket] = backlog;
        }
        return this;
    }

    /**
     * Returns the groups.
     *
     * @return one per key that had data in the range, sorted by key
     */
    public List<Group> groups() {
        return groups.entrySet().stream().map(entry -> entry.getValue().build(entry.getKey())).toList();
    }

    private int bucketOf(Instant time) {
        if (time == null || times.isEmpty()) {
            return -1;
        }
        long offset = time.toEpochMilli() - times.get(0).toEpochMilli();
        long index = Math.floorDiv(offset, stepMillis);
        return index >= 0 && index < times.size() ? (int) index : -1;
    }

    /** Running sums of one group. */
    private static final class GroupBuilder {
        private final long[] msgsIn;
        private final long[] msgsOut;
        private final long[] bytesIn;
        private final long[] bytesOut;
        private final Long[] backlog;
        private final Instant[] backlogAt;

        GroupBuilder(int buckets) {
            msgsIn = new long[buckets];
            msgsOut = new long[buckets];
            bytesIn = new long[buckets];
            bytesOut = new long[buckets];
            backlog = new Long[buckets];
            backlogAt = new Instant[buckets];
        }

        Group build(String key) {
            return new Group(key, boxed(msgsIn), boxed(msgsOut), boxed(bytesIn), boxed(bytesOut),
                    new ArrayList<>(Arrays.asList(backlog)));
        }

        private static List<Long> boxed(long[] values) {
            return Arrays.stream(values).boxed().toList();
        }
    }
}
