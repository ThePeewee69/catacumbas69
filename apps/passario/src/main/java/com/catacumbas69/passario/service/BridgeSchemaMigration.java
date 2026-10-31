package com.catacumbas69.passario.service;

import com.catacumbas69.passario.model.BridgeDocument;
import com.catacumbas69.passario.model.BridgeTrafficMinute;
import com.catacumbas69.passario.model.BridgeTrafficTotal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brings existing {@code bridges} documents to the current schema and creates the indexes of
 * {@code bridges}, {@code bridge_traffic_minutes} and {@code bridge_traffic_totals} (MONGO-2, MONGO-3).
 *
 * <p>Runs shortly after start-up and retries every 30 s until it succeeds, so the application starts
 * even when MongoDB is down (RES-1). Every step is idempotent.
 */
@Slf4j
@Component
public class BridgeSchemaMigration {

    private final MongoTemplate mongoTemplate;
    private final AuditLog audit;
    private volatile boolean completed;

    /**
     * Creates the migration.
     *
     * @param mongoTemplate template for bulk updates
     * @param audit audit log; each rewritten bridge is recorded as {@code MIGRATE_BRIDGE}
     */
    public BridgeSchemaMigration(MongoTemplate mongoTemplate, AuditLog audit) {
        this.mongoTemplate = mongoTemplate;
        this.audit = audit;
    }

    /** Runs the migration until it has succeeded once. */
    @Scheduled(initialDelayString = "PT1S", fixedDelayString = "PT30S")
    public void migrateIfNeeded() {
        if (completed) {
            return;
        }
        try {
            long versioned = addMissingVersions();
            long moved = moveLegacyDestinations();
            ensureIndexes();
            completed = true;
            log.info("Bridge schema up to date (versions added: {}, legacy destinations moved: {})", versioned, moved);
        } catch (RuntimeException e) {
            log.warn("Bridge schema migration pending, will retry: {}", e.getMessage());
        }
    }

    /**
     * Documents written before optimistic locking have no {@code version}; without one Spring Data would
     * treat them as new and an update would fail with a duplicate id.
     */
    private long addMissingVersions() {
        Query query = Query.query(Criteria.where("version").exists(false));
        return mongoTemplate.updateMulti(query, new Update().set("version", 0L), BridgeDocument.class)
                .getModifiedCount();
    }

    /** Rewrites the legacy single {@code destination} into {@code destinations}. */
    @SuppressWarnings("deprecation")
    private long moveLegacyDestinations() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("destination").exists(true),
                new Criteria().orOperator(
                        Criteria.where("destinations").exists(false),
                        Criteria.where("destinations").size(0))));
        List<BridgeDocument> legacy = mongoTemplate.find(query, BridgeDocument.class).stream()
                .filter(bridge -> bridge.getDestination() != null)
                .toList();
        legacy.forEach(bridge -> {
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(bridge.getId())),
                    new Update().set("destinations", List.of(bridge.getDestination())).unset("destination"),
                    BridgeDocument.class);
            BridgeDocument after = mongoTemplate.findById(bridge.getId(), BridgeDocument.class);
            if (after != null) {
                audit.bridgeChanged(AuditLog.SYSTEM_USER, AuditAction.MIGRATE_BRIDGE, bridge, after);
            }
        });
        return legacy.size();
    }

    private void ensureIndexes() {
        mongoTemplate.indexOps(BridgeDocument.class)
                .createIndex(new Index().on("name", Sort.Direction.ASC).unique().named("bridges_name_unique"));
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
    }
}
