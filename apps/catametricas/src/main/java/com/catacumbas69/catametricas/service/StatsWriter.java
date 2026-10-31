package com.catacumbas69.catametricas.service;

import static com.catacumbas69.catametricas.model.Schema.BACKLOG_BYTES;
import static com.catacumbas69.catametricas.model.Schema.BACKLOG_MSGS;
import static com.catacumbas69.catametricas.model.Schema.BASE_NAME;
import static com.catacumbas69.catametricas.model.Schema.BYTES_IN;
import static com.catacumbas69.catametricas.model.Schema.BYTES_OUT;
import static com.catacumbas69.catametricas.model.Schema.FIRST_SEEN_AT;
import static com.catacumbas69.catametricas.model.Schema.HOUR;
import static com.catacumbas69.catametricas.model.Schema.LAST_SEEN_AT;
import static com.catacumbas69.catametricas.model.Schema.MINUTE;
import static com.catacumbas69.catametricas.model.Schema.MSGS_IN;
import static com.catacumbas69.catametricas.model.Schema.MSGS_OUT;
import static com.catacumbas69.catametricas.model.Schema.NAMESPACE;
import static com.catacumbas69.catametricas.model.Schema.TENANT;
import static com.catacumbas69.catametricas.model.Schema.TOPIC;

import com.catacumbas69.catametricas.model.Schema;
import com.catacumbas69.catametricas.model.TopicSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Writes one collection to MongoDB with unordered bulk upserts (PERF-2, MONGO-5): traffic is added with
 * {@code $inc} to the minute, hour and all-time documents, backlog is set to the latest sample, and the
 * snapshot of every topic is replaced. Topics that disappeared are marked inactive.
 */
@Component
public class StatsWriter {

    private final MongoTemplate mongoTemplate;

    /**
     * Creates the writer.
     *
     * @param mongoTemplate template for bulk operations
     */
    public StatsWriter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Stores a reading.
     *
     * @param reading what was read from Pulsar
     * @param collectedAt when the reading started
     * @return the plan that was written
     */
    public CollectionPlan write(PulsarReading reading, Instant collectedAt) {
        Map<String, TopicSnapshot> previous = mongoTemplate.findAll(TopicSnapshot.class).stream()
                .collect(Collectors.toMap(TopicSnapshot::topic, Function.identity()));
        CollectionPlan plan = CollectionPlan.of(previous, reading);
        if (!plan.traffic().isEmpty()) {
            writeBuckets(Schema.MINUTES, MINUTE, collectedAt.truncatedTo(ChronoUnit.MINUTES), plan);
            writeBuckets(Schema.HOURS, HOUR, collectedAt.truncatedTo(ChronoUnit.HOURS), plan);
            writeTotals(plan, collectedAt);
            writeSnapshots(plan, collectedAt);
        }
        if (!plan.deactivate().isEmpty()) {
            mongoTemplate.updateMulti(Query.query(Criteria.where("_id").in(plan.deactivate())),
                    Update.update("active", false), TopicSnapshot.class);
        }
        return plan;
    }

    private void writeBuckets(String collection, String timeField, Instant bucket, CollectionPlan plan) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, collection);
        plan.traffic().forEach(traffic -> {
            TopicSample sample = traffic.sample();
            bulk.upsert(Query.query(Criteria.where(TOPIC).is(sample.topic()).and(timeField).is(bucket)),
                    names(traffic(new Update(), traffic), sample)
                            .set(BACKLOG_MSGS, sample.backlogMsgs())
                            .set(BACKLOG_BYTES, sample.backlogBytes()));
        });
        bulk.execute();
    }

    private void writeTotals(CollectionPlan plan, Instant collectedAt) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Schema.TOTALS);
        plan.traffic().forEach(traffic -> bulk.upsert(Query.query(Criteria.where("_id").is(traffic.sample().topic())),
                names(traffic(new Update(), traffic), traffic.sample())
                        .min(FIRST_SEEN_AT, collectedAt)
                        .max(LAST_SEEN_AT, collectedAt)));
        bulk.execute();
    }

    private void writeSnapshots(CollectionPlan plan, Instant collectedAt) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, TopicSnapshot.class);
        plan.traffic().forEach(traffic -> {
            TopicSnapshot snapshot = snapshotOf(traffic.sample(), collectedAt);
            bulk.replaceOne(Query.query(Criteria.where("_id").is(snapshot.topic())), snapshot,
                    FindAndReplaceOptions.options().upsert());
        });
        bulk.execute();
    }

    private static Update traffic(Update update, TopicTraffic traffic) {
        return update.inc(MSGS_IN, traffic.msgsIn())
                .inc(MSGS_OUT, traffic.msgsOut())
                .inc(BYTES_IN, traffic.bytesIn())
                .inc(BYTES_OUT, traffic.bytesOut());
    }

    private static Update names(Update update, TopicSample sample) {
        return update.set(TOPIC, sample.topic())
                .set(TENANT, sample.names().tenant())
                .set(NAMESPACE, sample.names().namespace())
                .set(BASE_NAME, sample.names().baseName());
    }

    static TopicSnapshot snapshotOf(TopicSample sample, Instant collectedAt) {
        TopicNames names = sample.names();
        return new TopicSnapshot(sample.topic(), names.tenant(), names.namespace(), names.name(), names.baseName(),
                true, collectedAt,
                sample.msgInCounter(), sample.msgOutCounter(), sample.bytesInCounter(), sample.bytesOutCounter(),
                sample.msgRateIn(), sample.msgRateOut(), sample.throughputIn(), sample.throughputOut(),
                sample.backlogMsgs(), sample.backlogBytes(), sample.storageSize(),
                sample.subscriptions(), sample.producers(), sample.consumers());
    }
}
