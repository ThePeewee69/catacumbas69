package com.catacumbas69.catavigia.service;

import static com.catacumbas69.catavigia.model.Schema.CHECK;
import static com.catacumbas69.catavigia.model.Schema.CLAIMED_AT;
import static com.catacumbas69.catavigia.model.Schema.CLOSED_AT;
import static com.catacumbas69.catavigia.model.Schema.KEY;
import static com.catacumbas69.catavigia.model.Schema.SUBJECT;
import static com.catacumbas69.catavigia.model.Schema.TYPE;

import com.catacumbas69.catavigia.config.CatavigiaProperties;
import com.catacumbas69.catavigia.model.Schema;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Creates the indexes of Catavigia's collections (MONGO-3): one open alert per key (unique, ADR 0012), the archive
 * by close time, type and subject, observations by check, and the TTL of task claims. Tried in the background when
 * the app is ready, and again before a task until it succeeds.
 */
@Component
public class CatavigiaIndexes implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(CatavigiaIndexes.class);

    private final MongoTemplate mongoTemplate;
    private final Duration runRetention;
    private volatile boolean created;

    /**
     * Creates the index initializer.
     *
     * @param mongoTemplate template for index operations
     * @param properties application properties, for the claim retention
     */
    public CatavigiaIndexes(MongoTemplate mongoTemplate, CatavigiaProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.runRetention = properties.tasks().runRetention();
    }

    /**
     * Tries once in the background; a failure is only logged (the next task tries again).
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Thread.ofVirtual().name("catavigia-indexes").start(() -> {
            try {
                ensure();
            } catch (RuntimeException e) {
                LOG.warn("MongoDB not prepared yet ({}); trying again before the first task", e.getMessage());
            }
        });
    }

    /** Creates the indexes once; later calls do nothing. Throws if MongoDB fails. */
    public synchronized void ensure() {
        if (created) {
            return;
        }
        mongoTemplate.indexOps(Schema.ALERTS).createIndex(asc(KEY).unique().named("alerts_key"));
        mongoTemplate.indexOps(Schema.ARCHIVE).createIndex(desc(CLOSED_AT).named("archive_closed"));
        mongoTemplate.indexOps(Schema.ARCHIVE).createIndex(asc(TYPE).on(CLOSED_AT, Sort.Direction.DESC)
                .named("archive_type_closed"));
        mongoTemplate.indexOps(Schema.ARCHIVE).createIndex(asc(SUBJECT).on(CLOSED_AT, Sort.Direction.DESC)
                .named("archive_subject_closed"));
        mongoTemplate.indexOps(Schema.OBSERVATIONS).createIndex(asc(CHECK).named("observations_check"));
        mongoTemplate.indexOps(Schema.TASK_RUNS).createIndex(asc(CLAIMED_AT).expire(runRetention)
                .named("task_runs_ttl"));
        created = true;
        LOG.info("Catavigia collections ready (task claims kept for {} h)", runRetention.toHours());
    }

    private static Index asc(String field) {
        return new Index().on(field, Sort.Direction.ASC);
    }

    private static Index desc(String field) {
        return new Index().on(field, Sort.Direction.DESC);
    }
}
