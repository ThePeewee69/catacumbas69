package com.catacumbas69.catametricas.service;

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
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Creates the indexes of Catametricas' collections (MONGO-3) before the first write; retried on the next
 * collection if MongoDB is unreachable. The per-minute collection has a TTL index that deletes minutes
 * older than {@code catametricas.collect.minute-retention}.
 */
@Component
public class CatametricasIndexes {

    private static final Logger LOG = LoggerFactory.getLogger(CatametricasIndexes.class);

    private final MongoTemplate mongoTemplate;
    private final Duration minuteRetention;
    private volatile boolean created;

    /**
     * Creates the index initializer.
     *
     * @param mongoTemplate template for index operations
     * @param properties application properties, for the minute retention
     */
    public CatametricasIndexes(MongoTemplate mongoTemplate, CatametricasProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.minuteRetention = properties.collect().minuteRetention();
    }

    /** Creates the indexes once; later calls do nothing. Throws if MongoDB rejects them. */
    public void ensure() {
        if (created) {
            return;
        }
        IndexOperations minutes = mongoTemplate.indexOps(Schema.MINUTES);
        minutes.createIndex(key(TOPIC, MINUTE).unique().named("minutes_topic_minute"));
        minutes.createIndex(key(TENANT, NAMESPACE, MINUTE).named("minutes_scope_minute"));
        minutes.createIndex(key(MINUTE).expire(minuteRetention).named("minutes_ttl"));

        IndexOperations hours = mongoTemplate.indexOps(Schema.HOURS);
        hours.createIndex(key(TOPIC, HOUR).unique().named("hours_topic_hour"));
        hours.createIndex(key(TENANT, NAMESPACE, HOUR).named("hours_scope_hour"));
        hours.createIndex(key(HOUR).named("hours_hour"));

        mongoTemplate.indexOps(Schema.TOTALS).createIndex(key(TENANT, NAMESPACE).named("totals_scope"));
        mongoTemplate.indexOps(Schema.SNAPSHOTS).createIndex(key(TENANT, NAMESPACE).named("snapshots_scope"));
        created = true;
        LOG.info("Catametricas indexes ready (minute history kept for {} days)", minuteRetention.toDays());
    }

    private static Index key(String... fields) {
        Index index = new Index();
        for (String field : fields) {
            index.on(field, Sort.Direction.ASC);
        }
        return index;
    }
}
