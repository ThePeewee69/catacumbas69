package com.catacumbas69.catavigia.service;

import com.catacumbas69.catacommons.alerts.AlertSetting;
import com.catacumbas69.catacommons.consumers.InstanceId;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.checks.Check;
import com.catacumbas69.catavigia.checks.Finding;
import com.catacumbas69.catavigia.checks.SourceUnavailableException;
import com.catacumbas69.catavigia.model.CheckTask;
import com.catacumbas69.catavigia.model.TaskRun;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Runs one check task (ADR 0012): claims it, runs the check and gives what it saw to the {@link AlertEngine}.
 *
 * <ul>
 *   <li>The claim is an insert into {@code catavigia_task_runs} keyed by the task id; a duplicate key means
 *       another replica has it, and the task is skipped.</li>
 *   <li>If the check cannot read its source, or MongoDB fails, the claim is removed and the task is delivered
 *       again later; after {@code dlq.max-deliveries} deliveries it goes to the DLQ (orch.md decision 79).</li>
 *   <li>A task of an old round still runs, but changes no alert once a newer round has been counted.</li>
 * </ul>
 */
@Service
public class CheckRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CheckRunner.class);

    /** What happened to one task. */
    public enum Outcome {
        /** Checked by this replica. */
        DONE,
        /** Already claimed by a replica; skipped. */
        DUPLICATE,
        /** Not a usable task; the consumer sends it straight to the DLQ. */
        INVALID,
        /** The check's source, Pulsar or MongoDB failed; delivered again later. */
        RETRY
    }

    private final Map<String, Check> checks;
    private final AlertEngine engine;
    private final MongoTemplate mongoTemplate;
    private final CatavigiaIndexes indexes;
    private final SharedSettings settings;
    private final String instance;
    private final Clock clock;

    /**
     * Creates the runner.
     *
     * @param checks every check
     * @param engine opens and closes alerts
     * @param mongoTemplate template for the claims
     * @param indexes creates the indexes before the first task
     * @param settings the settings topic, for the debounce
     * @param instance this replica, stored in the claims
     * @param clock time source
     */
    public CheckRunner(List<Check> checks, AlertEngine engine, MongoTemplate mongoTemplate, CatavigiaIndexes indexes,
                       ObjectProvider<SharedSettings> settings, InstanceId instance, Clock clock) {
        this.checks = checks.stream().collect(Collectors.toMap(Check::name, Function.identity()));
        this.engine = engine;
        this.mongoTemplate = mongoTemplate;
        this.indexes = indexes;
        this.settings = settings.getIfAvailable();
        this.instance = instance.id();
        this.clock = clock;
    }

    /**
     * Returns the names of every check, for the tasks of a round.
     *
     * @return the names, sorted
     */
    public List<String> names() {
        return checks.keySet().stream().sorted().toList();
    }

    /**
     * Runs one task.
     *
     * @param task the task, or {@code null} if the message could not be read
     * @return what happened
     */
    public Outcome handle(CheckTask task) {
        Check check = task == null ? null : checks.get(task.check());
        if (task == null || !task.complete() || check == null) {
            if (task != null) {
                LOG.error("Check task is not valid: {}", task);
            }
            return Outcome.INVALID;
        }
        Instant started = clock.instant();
        try {
            indexes.ensure();
            mongoTemplate.insert(TaskRun.claim(task, started, instance));
        } catch (DuplicateKeyException e) {
            LOG.debug("Task {} already claimed", task.id());
            return Outcome.DUPLICATE;
        } catch (RuntimeException e) {
            LOG.warn("Task {} not claimed, will be delivered again: {}", task.id(), e.getMessage());
            return Outcome.RETRY;
        }
        try {
            List<Finding> findings = check.run();
            AlertEngine.Result result = engine.apply(check, task.roundStart(), findings,
                    (int) AlertSetting.DEBOUNCE.value(settings));
            long millis = Duration.between(started, clock.instant()).toMillis();
            finish(task, millis, findings.size());
            LOG.debug("Checked {}: {} problem(s), {} opened, {} closed, {} late in {} ms", task.id(),
                    result.findings(), result.opened(), result.closed(), result.stale(), millis);
            return Outcome.DONE;
        } catch (SourceUnavailableException e) {
            release(task);
            LOG.warn("Task {} could not read its source, will be delivered again: {}", task.id(), e.getMessage());
            return Outcome.RETRY;
        } catch (RuntimeException e) {
            release(task);
            LOG.warn("Task {} failed, will be delivered again: {}", task.id(), e.getMessage());
            return Outcome.RETRY;
        }
    }

    private void finish(CheckTask task, long millis, int findings) {
        try {
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(task.id())),
                    new Update().set("finishedAt", clock.instant()).set("durationMillis", millis)
                            .set("findings", findings), TaskRun.class);
        } catch (RuntimeException e) {
            LOG.debug("Result of task {} not stored: {}", task.id(), e.getMessage());
        }
    }

    private void release(CheckTask task) {
        try {
            mongoTemplate.remove(Query.query(Criteria.where("_id").is(task.id())), TaskRun.class);
        } catch (RuntimeException e) {
            LOG.debug("Claim of task {} not released: {}", task.id(), e.getMessage());
        }
    }
}
