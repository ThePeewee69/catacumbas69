package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.model.CollectTask;
import com.catacumbas69.catametricas.model.TaskRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Runs one collection task (ADR 0012): claims it, reads the namespace from Pulsar and writes the result.
 *
 * <ul>
 *   <li>The claim is an insert into {@code catametricas_task_runs} keyed by the task id; a duplicate key means
 *       another instance has the task, so this one skips it.</li>
 *   <li>The write is idempotent ({@link StatsWriter}): a late or repeated task never counts traffic twice, so
 *       every task is run, however old.</li>
 *   <li>If reading or writing fails, the claim is removed and the task is delivered again later. A namespace
 *       that no longer exists is not a failure: its topics are marked as gone.</li>
 * </ul>
 *
 * <p>Metrics: {@code catametricas.tasks{outcome=done|duplicate|invalid|retry}} and
 * {@code catametricas.collect.duration} (one namespace).
 */
@Service
public class StatsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(StatsCollector.class);

    /** What happened to one task. */
    public enum Outcome {
        /** Collected and stored by this instance. */
        DONE,
        /** Already claimed by an instance; skipped. */
        DUPLICATE,
        /** Not a usable task; dropped. */
        INVALID,
        /** Pulsar or MongoDB failed; delivered again later. */
        RETRY
    }

    private final CollectInterval interval;
    private final CatametricasIndexes indexes;
    private final PulsarStatsReader reader;
    private final StatsWriter writer;
    private final MongoTemplate mongoTemplate;
    private final CollectorState state;
    private final Clock clock;
    private final Timer duration;
    private final Counter done;
    private final Counter duplicates;
    private final Counter invalid;
    private final Counter retries;

    /**
     * Creates the collector.
     *
     * @param interval collection interval, for logs and events
     * @param indexes prepares the collections
     * @param reader reads Pulsar
     * @param writer writes MongoDB
     * @param mongoTemplate template for the task claims
     * @param state this instance's collection state and events
     * @param clock time source
     * @param meterRegistry metrics registry
     */
    public StatsCollector(CollectInterval interval, CatametricasIndexes indexes, PulsarStatsReader reader,
                          StatsWriter writer, MongoTemplate mongoTemplate, CollectorState state, Clock clock,
                          MeterRegistry meterRegistry) {
        this.interval = interval;
        this.indexes = indexes;
        this.reader = reader;
        this.writer = writer;
        this.mongoTemplate = mongoTemplate;
        this.state = state;
        this.clock = clock;
        this.duration = Timer.builder("catametricas.collect.duration")
                .description("Time to read and store the statistics of one namespace").register(meterRegistry);
        this.done = counter(meterRegistry, "done");
        this.duplicates = counter(meterRegistry, "duplicate");
        this.invalid = counter(meterRegistry, "invalid");
        this.retries = counter(meterRegistry, "retry");
    }

    /**
     * Runs a task. Never throws.
     *
     * @param task the task, or {@code null} if the message could not be read
     * @return what happened
     */
    public Outcome handle(CollectTask task) {
        if (task == null || !task.complete()) {
            invalid.increment();
            if (task != null) {
                LOG.error("Collection task is not valid, dropped: {}", task);
            }
            return Outcome.INVALID;
        }
        int seconds = interval.seconds();
        Instant started = clock.instant();
        try {
            indexes.ensure();
            mongoTemplate.insert(TaskRun.claim(task, started, state.instance()));
        } catch (DuplicateKeyException e) {
            duplicates.increment();
            LOG.debug("Task {} already claimed", task.id());
            return Outcome.DUPLICATE;
        } catch (RuntimeException e) {
            retries.increment();
            LOG.warn("Task {} not claimed, will be delivered again: {}", task.id(), e.getMessage());
            state.failed("claim", "MongoDB: " + e.getMessage(), seconds);
            return Outcome.RETRY;
        }
        try {
            PulsarReading reading = read(task.namespace());
            StatsWriter.Result result = writer.write(reading, started);
            long millis = Duration.between(started, clock.instant()).toMillis();
            TaskRun run = finish(task, started, millis, reading, result);
            duration.record(Duration.ofMillis(millis));
            done.increment();
            LOG.info("Collected {}: {} topic(s) in {} ms ({} counted, {} already newer, {} failed, {} gone)",
                    task.id(), reading.samples().size(), millis, result.advanced(), result.skipped(),
                    reading.failedTopics().size(), result.deactivated());
            state.succeeded(run, seconds);
            return Outcome.DONE;
        } catch (PulsarAdminException | RuntimeException e) {
            release(task);
            retries.increment();
            LOG.warn("Task {} failed, will be delivered again: {}", task.id(), e.getMessage());
            state.failed("collect", e.getMessage(), seconds);
            return Outcome.RETRY;
        }
    }

    /** Reads a namespace; one that no longer exists reads as empty, so its topics are marked as gone. */
    private PulsarReading read(String namespace) throws PulsarAdminException {
        try {
            return reader.read(namespace);
        } catch (PulsarAdminException.NotFoundException e) {
            LOG.info("Namespace {} no longer exists; its topics are marked as gone", namespace);
            return new PulsarReading(namespace, List.of(), Set.of());
        }
    }

    private TaskRun finish(CollectTask task, Instant started, long millis, PulsarReading reading,
                           StatsWriter.Result result) {
        TaskRun claim = TaskRun.claim(task, started, state.instance());
        TaskRun run = new TaskRun(claim.id(), claim.namespace(), claim.bucket(), claim.claimedAt(), claim.instance(),
                clock.instant(), millis, reading.samples().size(), reading.failedTopics().size(), result.advanced());
        try {
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(task.id())),
                    Update.update("finishedAt", run.finishedAt())
                            .set("durationMillis", run.durationMillis())
                            .set("topics", run.topics())
                            .set("failedTopics", run.failedTopics())
                            .set("advancedTopics", run.advancedTopics()),
                    TaskRun.class);
        } catch (RuntimeException e) {
            LOG.debug("Result of task {} not recorded: {}", task.id(), e.getMessage());
        }
        return run;
    }

    /** Gives a failed task back, so it is run when delivered again. */
    private void release(CollectTask task) {
        try {
            mongoTemplate.remove(Query.query(Criteria.where("_id").is(task.id())
                    .and("instance").is(state.instance())), TaskRun.class);
        } catch (RuntimeException e) {
            LOG.warn("Claim of task {} not released ({}); the next task counts its traffic", task.id(),
                    e.getMessage());
        }
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("catametricas.tasks")
                .description("Collection tasks received, by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }
}
