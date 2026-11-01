package com.catacumbas69.cataudit.service;

import com.catacumbas69.cataudit.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates the {@code cataudit_events} indexes (MONGO-3), one per filter of the query API, each ending with the
 * time so pages come out in order. Retries every 30 s until it succeeds, so Cataudit starts while MongoDB is
 * down (RES-1). Index creation is idempotent.
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
            IndexOperations indexes = mongoTemplate.indexOps(AuditEvent.class);
            indexes.createIndex(new Index().on("time", Sort.Direction.DESC).named("time"));
            indexes.createIndex(byTime("app"));
            indexes.createIndex(byTime("subject"));
            indexes.createIndex(byTime("actor"));
            indexes.createIndex(new Index().on("entity", Sort.Direction.ASC).on("action", Sort.Direction.ASC)
                    .on("time", Sort.Direction.DESC).named("entity_action_time"));
            created = true;
            LOG.info("Audit indexes ready");
        } catch (RuntimeException e) {
            LOG.warn("Audit indexes pending, will retry: {}", e.getMessage());
        }
    }

    private static Index byTime(String field) {
        return new Index().on(field, Sort.Direction.ASC).on("time", Sort.Direction.DESC).named(field + "_time");
    }
}
