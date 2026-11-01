package com.catacumbas69.catametricas.service;

import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catametricas.model.CollectTask;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes the collection tasks of one interval (ADR 0012): one per namespace, keyed and identified by
 * {@code <tenant>/<namespace>@<interval start>}. Every instance does this; the copies are expected, and the
 * claim in {@link StatsCollector} keeps the work single.
 */
@Component
public class CollectTaskPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(CollectTaskPublisher.class);

    private final PulsarStatsReader reader;
    private final RetryingSender<CollectTask> sender;
    private final CollectInterval interval;
    private final CollectorState state;
    private final Clock clock;

    /**
     * Creates the publisher.
     *
     * @param reader lists the namespaces
     * @param sender sends to the task topic
     * @param interval collection interval
     * @param state this instance's collection state
     * @param clock time source
     */
    public CollectTaskPublisher(PulsarStatsReader reader, RetryingSender<CollectTask> sender,
                                CollectInterval interval, CollectorState state, Clock clock) {
        this.reader = reader;
        this.sender = sender;
        this.interval = interval;
        this.state = state;
        this.clock = clock;
    }

    /**
     * Returns the start of the interval that contains a time. Intervals are aligned to the epoch, so every
     * instance computes the same start.
     *
     * @param time a time
     * @param seconds interval length
     * @return the start of its interval
     */
    public static Instant bucketOf(Instant time, int seconds) {
        return Instant.ofEpochSecond(Math.floorDiv(time.getEpochSecond(), seconds) * seconds);
    }

    /** Publishes one task per namespace for the current interval. Never throws. */
    public void publish() {
        int seconds = interval.seconds();
        Instant bucket = bucketOf(clock.instant(), seconds);
        List<String> namespaces;
        try {
            namespaces = reader.namespaces();
        } catch (PulsarAdminException | RuntimeException e) {
            LOG.warn("Tasks for {} not published, namespaces not listed: {}", bucket, e.getMessage());
            state.failed("schedule", e.getMessage(), seconds);
            return;
        }
        for (String namespace : namespaces) {
            CollectTask task = CollectTask.of(namespace, bucket, seconds);
            sender.send(task.id(), task);
        }
        LOG.debug("Published {} collection task(s) for {}", namespaces.size(), bucket);
    }
}
