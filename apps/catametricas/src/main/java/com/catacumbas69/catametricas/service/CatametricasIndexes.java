package com.catacumbas69.catametricas.service;

import static com.catacumbas69.catametricas.model.Schema.BASE_NAME;
import static com.catacumbas69.catametricas.model.Schema.CLAIMED_AT;
import static com.catacumbas69.catametricas.model.Schema.COLLECTED_AT;
import static com.catacumbas69.catametricas.model.Schema.HOUR;
import static com.catacumbas69.catametricas.model.Schema.MINUTE;
import static com.catacumbas69.catametricas.model.Schema.NAMESPACE;
import static com.catacumbas69.catametricas.model.Schema.TENANT;
import static com.catacumbas69.catametricas.model.Schema.TOPIC;

import com.catacumbas69.catametricas.config.CatametricasProperties;
import com.catacumbas69.catametricas.model.Schema;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Creates the indexes of Catametricas' collections (MONGO-3). Tried once in the background when the app is ready,
 * and again before every task until it succeeds. The
 * per-minute collection has a TTL index that deletes minutes older than {@code catametricas.collect.minute-retention};
 * task claims are deleted after {@code catametricas.tasks.run-retention}.
 */
@Component
public class CatametricasIndexes implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(CatametricasIndexes.class);

    private final MongoTemplate mongoTemplate;
    private final Duration minuteRetention;
    private final Duration runRetention;
    private volatile boolean created;

    /**
     * Creates the index initializer.
     *
     * @param mongoTemplate template for index operations
     * @param properties application properties, for the retentions
     */
    public CatametricasIndexes(MongoTemplate mongoTemplate, CatametricasProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.minuteRetention = properties.collect().minuteRetention();
        this.runRetention = properties.tasks().runRetention();
    }

    /**
     * Tries once in the background; a failure is only logged (the next task tries again).
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Thread.ofVirtual().name("catametricas-indexes").start(() -> {
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

        IndexOperations minutes = mongoTemplate.indexOps(Schema.MINUTES);
        minutes.createIndex(key(TOPIC, MINUTE).unique().named("minutes_topic_minute"));
        minutes.createIndex(key(TENANT, NAMESPACE, MINUTE).named("minutes_scope_minute"));
        minutes.createIndex(key(TENANT, NAMESPACE, BASE_NAME, MINUTE).named("minutes_scope_topic_minute"));
        minutes.createIndex(key(MINUTE).expire(minuteRetention).named("minutes_ttl"));

        IndexOperations hours = mongoTemplate.indexOps(Schema.HOURS);
        hours.createIndex(key(TOPIC, HOUR).unique().named("hours_topic_hour"));
        hours.createIndex(key(TENANT, NAMESPACE, HOUR).named("hours_scope_hour"));
        hours.createIndex(key(TENANT, NAMESPACE, BASE_NAME, HOUR).named("hours_scope_topic_hour"));
        hours.createIndex(key(HOUR).named("hours_hour"));

        mongoTemplate.indexOps(Schema.TOTALS).createIndex(key(TENANT, NAMESPACE).named("totals_scope"));
        IndexOperations snapshots = mongoTemplate.indexOps(Schema.SNAPSHOTS);
        snapshots.createIndex(key(TENANT, NAMESPACE).named("snapshots_scope"));
        snapshots.createIndex(key(COLLECTED_AT).named("snapshots_collected"));

        mongoTemplate.indexOps(Schema.TASK_RUNS)
                .createIndex(key(CLAIMED_AT).expire(runRetention).named("task_runs_ttl"));
        created = true;
        LOG.info("Catametricas collections ready (minute history kept for {} days, task claims for {} h)",
                minuteRetention.toDays(), runRetention.toHours());
    }

    private static Index key(String... fields) {
        Index index = new Index();
        for (String field : fields) {
            index.on(field, Sort.Direction.ASC);
        }
        return index;
    }
}
