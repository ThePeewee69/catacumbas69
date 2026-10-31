package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.TrafficRole;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory message counter for one topic of one bridge, split by minute.
 *
 * <p>{@link #add(long)} is called on the hot path from Pulsar I/O threads: it only increments atomics.
 * Counts are moved to MongoDB by {@link TrafficRecorder#flush()}. Writers only ever touch the current
 * minute, so a cell two or more minutes old can be removed safely once drained.
 */
public final class TopicTraffic {

    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final String bridgeId;
    private final TrafficRole role;
    private final String topic;
    private final Clock clock;
    private final Map<Long, Cell> cells = new ConcurrentHashMap<>();
    private volatile String bridgeName;

    TopicTraffic(String bridgeId, String bridgeName, TrafficRole role, String topic, Clock clock) {
        this.bridgeId = bridgeId;
        this.bridgeName = bridgeName;
        this.role = role;
        this.topic = topic;
        this.clock = clock;
    }

    /**
     * Counts one message.
     *
     * @param bytes payload size
     */
    public void add(long bytes) {
        Cell cell = cells.computeIfAbsent(clock.millis() / MILLIS_PER_MINUTE, minute -> new Cell());
        cell.messages.incrementAndGet();
        cell.bytes.addAndGet(bytes);
    }

    void rename(String name) {
        this.bridgeName = name;
    }

    /** Moves every non-zero count into {@code out}; drops cells that can no longer receive writes. */
    void drainInto(List<Drained> out) {
        long currentMinute = clock.millis() / MILLIS_PER_MINUTE;
        cells.forEach((minute, cell) -> {
            long messages = cell.messages.getAndSet(0);
            long bytes = cell.bytes.getAndSet(0);
            if (messages > 0 || bytes > 0) {
                out.add(new Drained(this, minute, messages, bytes));
            }
            if (minute < currentMinute - 1) {
                cells.remove(minute, cell);
            }
        });
    }

    /** Puts counts back after a failed write, so nothing is lost (RES-6). */
    void restore(Drained drained) {
        Cell cell = cells.computeIfAbsent(drained.minute(), minute -> new Cell());
        cell.messages.addAndGet(drained.messages());
        cell.bytes.addAndGet(drained.bytes());
    }

    String bridgeId() {
        return bridgeId;
    }

    String bridgeName() {
        return bridgeName;
    }

    TrafficRole role() {
        return role;
    }

    String topic() {
        return topic;
    }

    /**
     * Counts taken from one minute of one series, ready to be written.
     *
     * @param series where the counts came from
     * @param minute minutes since the epoch
     * @param messages message count
     * @param bytes payload bytes
     */
    record Drained(TopicTraffic series, long minute, long messages, long bytes) {

        long minuteStartMillis() {
            return minute * MILLIS_PER_MINUTE;
        }
    }

    private static final class Cell {
        private final AtomicLong messages = new AtomicLong();
        private final AtomicLong bytes = new AtomicLong();
    }
}
