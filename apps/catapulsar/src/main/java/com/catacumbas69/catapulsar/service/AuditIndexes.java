package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.model.AuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates the {@code audit_logs} indexes (MONGO-3). Retries every 30 s until it succeeds, so the
 * application starts even when MongoDB is down (RES-1). Index creation is idempotent.
 */
@Component
public class AuditIndexes {

    private static final Logger LOG = LoggerFactory.getLogger(AuditIndexes.class);

    private final MongoTemplate mongoTemplate;
    private volatile boolean created;

    /**
     * Creates the component.
     *
     * @param mongoTemplate template for index operations
     */
    public AuditIndexes(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Ensures the indexes exist, once. */
    @Scheduled(initialDelayString = "PT1S", fixedDelayString = "PT30S")
    public void ensureIndexes() {
        if (created) {
            return;
        }
        try {
            IndexOperations indexes = mongoTemplate.indexOps(AuditEntry.class);
            indexes.createIndex(new Index().on("timestamp", Sort.Direction.DESC).named("audit_timestamp"));
            indexes.createIndex(new Index().on("topic", Sort.Direction.ASC)
                    .on("timestamp", Sort.Direction.DESC).named("audit_topic_timestamp"));
            indexes.createIndex(new Index().on("action", Sort.Direction.ASC)
                    .on("timestamp", Sort.Direction.DESC).named("audit_action_timestamp"));
            created = true;
            LOG.info("Audit indexes ready");
        } catch (RuntimeException e) {
            LOG.warn("Audit indexes pending, will retry: {}", e.getMessage());
        }
    }
}
