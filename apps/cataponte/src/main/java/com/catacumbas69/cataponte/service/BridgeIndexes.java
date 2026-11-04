package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.AreaDocument;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.model.BridgeTrafficMinute;
import com.catacumbas69.cataponte.model.BridgeTrafficTotal;
import com.catacumbas69.cataponte.model.WorkerStatusDocument;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates the indexes of Cataponte's collections (MONGO-3): the unique bridge name, the upsert keys of the traffic
 * counters and the TTL of the worker status reports.
 *
 * <p>Runs shortly after start-up and retries every 30 s until it succeeds, so the application starts even when
 * MongoDB is down (RES-1). Creating an index that exists is a no-op, so several replicas may run it at once.
 */
@Slf4j
@Component
public class BridgeIndexes {

    /** Reports of replicas that died without removing them are deleted after this long. */
    static final Duration WORKER_STATUS_TTL = Duration.ofMinutes(5);

    private final MongoTemplate mongoTemplate;
    private volatile boolean created;

    /**
     * Creates the index initializer.
     *
     * @param mongoTemplate template for index operations
     */
    public BridgeIndexes(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Creates the indexes until it has succeeded once. */
    @Scheduled(initialDelayString = "PT1S", fixedDelayString = "PT30S")
    public void ensureIfNeeded() {
        if (created) {
            return;
        }
        try {
            ensure();
            created = true;
            log.info("Bridge indexes ready");
        } catch (RuntimeException e) {
            log.warn("Bridge indexes not created yet, will retry: {}", e.getMessage());
        }
    }

    private void ensure() {
        mongoTemplate.indexOps(BridgeDocument.class)
                .createIndex(new Index().on("name", Sort.Direction.ASC).unique().named("bridges_name_unique"));
        mongoTemplate.indexOps(BridgeDocument.class)
                .createIndex(new Index().on("areaId", Sort.Direction.ASC).named("bridges_area"));
        mongoTemplate.indexOps(AreaDocument.class)
                .createIndex(new Index().on("name", Sort.Direction.ASC).unique().named("areas_name_unique"));
        // Upsert keys of the traffic collections; also serve the per-bridge queries.
        mongoTemplate.indexOps(BridgeTrafficMinute.class).createIndex(new Index()
                .on("bridgeId", Sort.Direction.ASC).on("role", Sort.Direction.ASC)
                .on("topic", Sort.Direction.ASC).on("minute", Sort.Direction.ASC)
                .unique().named("traffic_minutes_key"));
        mongoTemplate.indexOps(BridgeTrafficMinute.class).createIndex(new Index()
                .on("bridgeId", Sort.Direction.ASC).on("minute", Sort.Direction.ASC)
                .named("traffic_minutes_bridge_minute"));
        mongoTemplate.indexOps(BridgeTrafficTotal.class).createIndex(new Index()
                .on("bridgeId", Sort.Direction.ASC).on("role", Sort.Direction.ASC).on("topic", Sort.Direction.ASC)
                .unique().named("traffic_totals_key"));
        mongoTemplate.indexOps(WorkerStatusDocument.class).createIndex(new Index()
                .on("updatedAt", Sort.Direction.ASC).expire(WORKER_STATUS_TTL).named("worker_status_ttl"));
    }
}
