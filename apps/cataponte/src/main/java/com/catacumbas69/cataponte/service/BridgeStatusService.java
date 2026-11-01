package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.config.CataponteProperties;
import com.catacumbas69.cataponte.dto.BridgeRuntimeStatus;
import com.catacumbas69.cataponte.dto.BridgeStatusResponse;
import com.catacumbas69.cataponte.model.WorkerStatusDocument;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Answers {@code GET /api/v1/bridges/status} for every replica (ADR 0012): this replica's live worker status plus
 * the reports other replicas wrote to {@code cataponte_worker_status} in the last
 * {@code cataponte.status.stale-after}, merged per bridge. If MongoDB cannot be read, only this replica's status is
 * returned.
 */
@Slf4j
@Service
public class BridgeStatusService {

    /** States from the most to the least advanced; a bridge shows the most advanced state of its replicas. */
    private static final List<BridgeState> PRIORITY = List.of(BridgeState.RUNNING, BridgeState.STARTING,
            BridgeState.STOPPING, BridgeState.FAILED, BridgeState.BLOCKED, BridgeState.STOPPED);

    private final MongoTemplate mongoTemplate;
    private final BridgeManager manager;
    private final CataponteInstance instance;
    private final Duration staleAfter;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param mongoTemplate template for the reports
     * @param manager this replica's workers
     * @param instance this replica's identity
     * @param properties application properties, for the staleness limit
     * @param clock time source
     */
    public BridgeStatusService(MongoTemplate mongoTemplate, BridgeManager manager, CataponteInstance instance,
                               CataponteProperties properties, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.manager = manager;
        this.instance = instance;
        this.staleAfter = properties.status().staleAfter();
        this.clock = clock;
    }

    /**
     * Returns the status of every bridge over all replicas.
     *
     * @return the status
     */
    public BridgeStatusResponse status() {
        Instant now = clock.instant();
        List<WorkerStatusDocument> reports = new ArrayList<>();
        manager.getStatuses().forEach(status -> reports.add(toDocument(status, instance.id(), now)));
        try {
            reports.addAll(mongoTemplate.find(Query.query(Criteria.where("instance").ne(instance.id())
                    .and("updatedAt").gte(now.minus(staleAfter))), WorkerStatusDocument.class));
        } catch (RuntimeException e) {
            log.debug("Status of the other replicas not read: {}", e.getMessage());
        }
        List<BridgeRuntimeStatus> merged = merge(reports);
        int active = (int) merged.stream().filter(BridgeRuntimeStatus::running).count();
        return new BridgeStatusResponse(active, merged);
    }

    /**
     * Turns a replica's worker status into the report it writes.
     *
     * @param status worker status
     * @param instance replica
     * @param now report time
     * @return the report
     */
    public static WorkerStatusDocument toDocument(BridgeWorkerStatus status, String instance, Instant now) {
        return new WorkerStatusDocument(WorkerStatusDocument.idOf(status.bridgeId(), instance), status.bridgeId(),
                instance, status.name(), status.state() == null ? null : status.state().name(), status.running(),
                status.active(), status.messagesForwarded(), status.errors(), status.inFlight(), status.startedAt(),
                status.sourceTopic(), status.destinationTopics(), status.ordered(), status.lastError(),
                status.nextRetryAt(), now);
    }

    /**
     * Merges the reports of every replica, one result per bridge.
     *
     * @param reports reports, at most one per bridge and replica
     * @return one status per bridge, in the order bridges were first seen
     */
    static List<BridgeRuntimeStatus> merge(Collection<WorkerStatusDocument> reports) {
        Map<String, List<WorkerStatusDocument>> byBridge = reports.stream().collect(Collectors.groupingBy(
                WorkerStatusDocument::bridgeId, LinkedHashMap::new, Collectors.toList()));
        return byBridge.values().stream().map(BridgeStatusService::mergeBridge).toList();
    }

    private static BridgeRuntimeStatus mergeBridge(List<WorkerStatusDocument> replicas) {
        WorkerStatusDocument first = replicas.get(0);
        boolean running = replicas.stream().anyMatch(WorkerStatusDocument::running);
        String lastError = replicas.stream().filter(report -> !report.running())
                .map(WorkerStatusDocument::lastError).filter(Objects::nonNull).findFirst()
                .orElse(replicas.stream().map(WorkerStatusDocument::lastError).filter(Objects::nonNull).findFirst()
                        .orElse(null));
        return new BridgeRuntimeStatus(
                first.bridgeId(),
                first.name(),
                state(replicas),
                running,
                replicas.stream().mapToLong(WorkerStatusDocument::messagesForwarded).sum(),
                replicas.stream().mapToLong(WorkerStatusDocument::errors).sum(),
                replicas.stream().mapToInt(WorkerStatusDocument::inFlight).sum(),
                replicas.stream().filter(WorkerStatusDocument::running).map(WorkerStatusDocument::startedAt)
                        .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
                first.sourceTopic(),
                first.destinationTopics(),
                first.ordered(),
                lastError,
                replicas.stream().map(WorkerStatusDocument::nextRetryAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null),
                replicas.size(),
                (int) replicas.stream().filter(WorkerStatusDocument::active).count());
    }

    private static String state(List<WorkerStatusDocument> replicas) {
        for (BridgeState candidate : PRIORITY) {
            if (replicas.stream().anyMatch(report -> candidate.name().equals(report.state()))) {
                return candidate.name();
            }
        }
        return replicas.get(0).state();
    }
}
