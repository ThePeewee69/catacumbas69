package com.catacumbas69.catametricas.service;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catametricas.model.CollectorStatus;
import com.catacumbas69.catametricas.model.Schema;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs one collection: takes the lease, reads every topic from Pulsar and writes the result. A failed
 * collection is logged and retried at the next interval; nothing is written halfway for the snapshot
 * deltas because the snapshots are only replaced together with the traffic they produced.
 *
 * <p>Only changes of state are published as events on {@code catacumbas69/catametricas/events} (ADR 0008):
 * {@code collection.failed} on the first failure of this instance (or after a success) and
 * {@code collection.recovered} on the first success after a failure, both with subject {@value #SUBJECT}.
 */
@Service
public class StatsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(StatsCollector.class);
    private static final int LEASE_INTERVALS = 3;
    /** Subject of the collection events. */
    static final String SUBJECT = "collector";
    private static final String ENTITY = "collection";
    private static final String ACTOR = "catametricas";

    private final CollectorLock lock;
    private final CollectInterval interval;
    private final CatametricasIndexes indexes;
    private final PulsarStatsReader reader;
    private final StatsWriter writer;
    private final MongoTemplate mongoTemplate;
    private final EventPublisher events;
    private final Clock clock;
    private final Timer duration;
    private final Counter failures;
    private final AtomicInteger topics = new AtomicInteger();
    private volatile boolean leader;
    private volatile CollectorStatus lastStatus;
    private volatile String lastError;
    private volatile Boolean healthy;

    /**
     * Creates the collector.
     *
     * @param lock collector lease
     * @param interval collection interval
     * @param indexes index initializer
     * @param reader reads Pulsar
     * @param writer writes MongoDB
     * @param mongoTemplate template for the status document
     * @param events publishes failure and recovery events
     * @param clock time source
     * @param meterRegistry metrics registry
     */
    public StatsCollector(CollectorLock lock, CollectInterval interval, CatametricasIndexes indexes,
                          PulsarStatsReader reader, StatsWriter writer, MongoTemplate mongoTemplate,
                          EventPublisher events, Clock clock, MeterRegistry meterRegistry) {
        this.lock = lock;
        this.interval = interval;
        this.indexes = indexes;
        this.reader = reader;
        this.writer = writer;
        this.mongoTemplate = mongoTemplate;
        this.events = events;
        this.clock = clock;
        this.duration = Timer.builder("catametricas.collect.duration")
                .description("Time to read and store all topic statistics").register(meterRegistry);
        this.failures = Counter.builder("catametricas.collect.failures")
                .description("Collections that failed").register(meterRegistry);
        Gauge.builder("catametricas.topics", topics, AtomicInteger::get)
                .description("Topics read in the last collection").register(meterRegistry);
    }

    /** Collects if this instance holds (or can take) the lease. Never throws. */
    public void collectIfLeader() {
        int seconds = interval.seconds();
        try {
            leader = lock.tryAcquire(Duration.ofSeconds((long) seconds * LEASE_INTERVALS));
        } catch (RuntimeException e) {
            leader = false;
            lastError = "MongoDB: " + e.getMessage();
            LOG.warn("Collection skipped, lease not checked: {}", e.getMessage());
            failed("lease", lastError, seconds);
            return;
        }
        if (!leader) {
            LOG.debug("Another instance holds the collector lease");
            return;
        }
        Instant started = clock.instant();
        try {
            indexes.ensure();
            PulsarReading reading = reader.read();
            CollectionPlan plan = writer.write(reading, started);
            long millis = Duration.between(started, clock.instant()).toMillis();
            CollectorStatus status = new CollectorStatus(Schema.STATUS_ID, clock.instant(), millis,
                    reading.samples().size(), reading.failedTopics().size(), seconds, lock.owner());
            mongoTemplate.save(status);
            duration.record(Duration.ofMillis(millis));
            topics.set(reading.samples().size());
            lastStatus = status;
            lastError = null;
            LOG.info("Collected {} topic(s) in {} ms ({} failed, {} gone); next in {} s", reading.samples().size(),
                    millis, reading.failedTopics().size(), plan.deactivate().size(), seconds);
            recovered(status);
        } catch (PulsarAdminException | RuntimeException e) {
            failures.increment();
            lastError = e.getMessage();
            LOG.warn("Collection failed, retrying in {} s: {}", seconds, e.getMessage());
            failed("collect", e.getMessage(), seconds);
        }
    }

    private void failed(String stage, String error, int seconds) {
        if (Boolean.FALSE.equals(healthy)) {
            return;
        }
        healthy = false;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stage", stage);
        details.put("error", error);
        details.put("intervalSeconds", seconds);
        details.put("instance", owner());
        publish("failed", details);
    }

    private void recovered(CollectorStatus status) {
        boolean wasFailing = Boolean.FALSE.equals(healthy);
        healthy = true;
        if (!wasFailing) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("topics", status.topics());
        details.put("failedTopics", status.failedTopics());
        details.put("durationMillis", status.durationMillis());
        details.put("intervalSeconds", status.intervalSeconds());
        details.put("instance", status.owner());
        publish("recovered", details);
    }

    private void publish(String action, Map<String, Object> details) {
        try {
            events.publish(ENTITY, action, SUBJECT, EventData.action(ACTOR, details));
        } catch (RuntimeException e) {
            LOG.warn("Event collection.{} not published: {}", action, e.getMessage());
        }
    }

    private String owner() {
        try {
            return lock.owner();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Tells whether this instance held the lease at the last attempt.
     *
     * @return {@code true} if it is the collecting instance
     */
    public boolean isLeader() {
        return leader;
    }

    /**
     * Returns the last successful collection of this instance.
     *
     * @return the status, or {@code null} if none yet
     */
    public CollectorStatus lastStatus() {
        return lastStatus;
    }

    /**
     * Returns the error of the last attempt.
     *
     * @return the message, or {@code null} if the last attempt succeeded
     */
    public String lastError() {
        return lastError;
    }
}
