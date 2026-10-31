package com.catacumbas69.passario.service;

import com.catacumbas69.passario.model.TrafficRole;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Collects per-topic, per-minute message counts from every bridge and writes them to MongoDB in batches
 * every {@code passario.traffic.flush-interval} (PERF-2: no database write per message).
 *
 * <p>If MongoDB cannot be written, the counts stay in memory and are retried on the next flush (RES-6).
 * Counts are also flushed on shutdown, after the bridges have stopped.
 */
@Slf4j
@Component
public class TrafficRecorder {

    private final TrafficWriter writer;
    private final Clock clock;
    private final Map<SeriesKey, TopicTraffic> series = new ConcurrentHashMap<>();

    /**
     * Creates the recorder.
     *
     * @param writer bulk writer to MongoDB
     * @param clock time source for minute buckets
     */
    public TrafficRecorder(TrafficWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * Returns the counter for one topic of one bridge, creating it on first use. Workers fetch their
     * counters once at start-up and keep them.
     *
     * @param bridgeId bridge id
     * @param bridgeName bridge name, stored with the counts
     * @param role source or destination
     * @param topic fully-qualified topic
     * @return the counter
     */
    public TopicTraffic series(String bridgeId, String bridgeName, TrafficRole role, String topic) {
        TopicTraffic counter = series.computeIfAbsent(new SeriesKey(bridgeId, role, topic),
                key -> new TopicTraffic(bridgeId, bridgeName, role, topic, clock));
        counter.rename(bridgeName);
        return counter;
    }

    /** Writes every pending count to MongoDB. Scheduled; safe to call directly (tests, shutdown). */
    @Scheduled(fixedDelayString = "${passario.traffic.flush-interval}")
    public synchronized void flush() {
        List<TopicTraffic.Drained> drained = new ArrayList<>();
        series.values().forEach(counter -> counter.drainInto(drained));
        if (drained.isEmpty()) {
            return;
        }
        try {
            writer.write(drained);
        } catch (RuntimeException e) {
            drained.forEach(entry -> entry.series().restore(entry));
            log.warn("Traffic counts not stored, will retry ({} minute bucket(s) pending): {}",
                    drained.size(), e.getMessage());
        }
    }

    /** Last flush on shutdown; runs after the bridge manager has stopped the workers. */
    @PreDestroy
    public void flushOnShutdown() {
        flush();
    }

    private record SeriesKey(String bridgeId, TrafficRole role, String topic) {
    }
}
