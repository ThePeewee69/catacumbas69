package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.WorkerStatusDocument;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes this replica's worker status to {@code cataponte_worker_status} every
 * {@code cataponte.status.write-interval}, one document per bridge, and removes the reports of bridges it no longer
 * runs (ADR 0012). On shutdown it removes all of its reports. Failures are only logged: the next write repeats them.
 */
@Slf4j
@Component
public class WorkerStatusReporter {

    private static final String INSTANCE = "instance";

    private final BridgeManager manager;
    private final MongoTemplate mongoTemplate;
    private final CataponteInstance instance;
    private final Clock clock;

    /**
     * Creates the reporter.
     *
     * @param manager this replica's workers
     * @param mongoTemplate template for the reports
     * @param instance this replica's identity
     * @param clock time source
     */
    public WorkerStatusReporter(BridgeManager manager, MongoTemplate mongoTemplate, CataponteInstance instance,
                                Clock clock) {
        this.manager = manager;
        this.mongoTemplate = mongoTemplate;
        this.instance = instance;
        this.clock = clock;
    }

    /** Writes the current status of every worker. Scheduled; never throws. */
    @Scheduled(fixedDelayString = "${cataponte.status.write-interval}")
    public void report() {
        Instant now = clock.instant();
        List<WorkerStatusDocument> reports = manager.getStatuses().stream()
                .map(status -> BridgeStatusService.toDocument(status, instance.id(), now))
                .toList();
        try {
            if (!reports.isEmpty()) {
                BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED,
                        WorkerStatusDocument.class);
                reports.forEach(report -> bulk.replaceOne(Query.query(Criteria.where("_id").is(report.id())),
                        report, FindAndReplaceOptions.options().upsert()));
                bulk.execute();
            }
            mongoTemplate.remove(Query.query(Criteria.where(INSTANCE).is(instance.id())
                    .and("bridgeId").nin(reports.stream().map(WorkerStatusDocument::bridgeId).toList())),
                    WorkerStatusDocument.class);
        } catch (RuntimeException e) {
            log.debug("Worker status not written: {}", e.getMessage());
        }
    }

    /** Removes this replica's reports so the others stop counting it at once. */
    @PreDestroy
    public void withdraw() {
        try {
            mongoTemplate.remove(Query.query(Criteria.where(INSTANCE).is(instance.id())), WorkerStatusDocument.class);
        } catch (RuntimeException e) {
            log.debug("Worker status not removed ({}); it expires on its own", e.getMessage());
        }
    }
}
