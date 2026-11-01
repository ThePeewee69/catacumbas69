package com.catacumbas69.catametricas.service;

import static com.catacumbas69.catametricas.model.Schema.ACTIVE;
import static com.catacumbas69.catametricas.model.Schema.BACKLOG_BYTES;
import static com.catacumbas69.catametricas.model.Schema.BACKLOG_MSGS;
import static com.catacumbas69.catametricas.model.Schema.BASE_NAME;
import static com.catacumbas69.catametricas.model.Schema.BYTES_IN;
import static com.catacumbas69.catametricas.model.Schema.BYTES_OUT;
import static com.catacumbas69.catametricas.model.Schema.COLLECTED_AT;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Writes what one task read from a namespace, so that a repeated or late task never counts traffic twice
 * (ADR 0012).
 *
 * <ol>
 *   <li>Each topic's snapshot is replaced only if it is older than the new sample: a conditional
 *       {@code findAndReplace} that returns the snapshot it replaced. Traffic is the difference between the two.
 *       A newer snapshot (another instance got there first) makes the replace fail, and nothing is counted.</li>
 *   <li>Only that traffic is added with {@code $inc} to the minute, hour and all-time documents, in unordered
 *       bulk upserts (PERF-2, MONGO-5); backlog is set to the sample.</li>
 *   <li>Active topics of the namespace that were not listed any more are marked inactive (their history is
 *       kept).</li>
 * </ol>
 *
 * <p>If MongoDB fails between steps 1 and 2 the traffic of that interval is lost for those topics, never
 * doubled.
 */
@Component
public class StatsWriter {

    private final MongoTemplate mongoTemplate;

    /**
     * Creates the writer.
     *
     * @param mongoTemplate template for MongoDB
     */
    public StatsWriter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Stores the reading of one namespace.
     *
     * @param reading what was read from Pulsar
     * @param sampledAt when the reading started
     * @return what was written
     */
    public Result write(PulsarReading reading, Instant sampledAt) {
        String[] parts = reading.namespace().split("/", 2);
        Criteria namespace = Criteria.where(TENANT).is(parts[0]).and(NAMESPACE).is(parts[1]);
        boolean baseline = !mongoTemplate.exists(Query.query(namespace), Schema.SNAPSHOTS);

        List<TopicTraffic> advanced = new ArrayList<>();
        for (TopicSample sample : reading.samples()) {
            advance(sample, sampledAt, baseline).ifPresent(advanced::add);
        }
        if (!advanced.isEmpty()) {
            writeBuckets(Schema.MINUTES, MINUTE, sampledAt.truncatedTo(ChronoUnit.MINUTES), advanced);
            writeBuckets(Schema.HOURS, HOUR, sampledAt.truncatedTo(ChronoUnit.HOURS), advanced);
            writeTotals(advanced, sampledAt);
        }

        Set<String> seen = new HashSet<>(reading.failedTopics());
        reading.samples().forEach(sample -> seen.add(sample.topic()));
        Query gone = Query.query(Criteria.where(TENANT).is(parts[0]).and(NAMESPACE).is(parts[1])
                .and(ACTIVE).is(true)
                .and("_id").nin(seen)
                .and(COLLECTED_AT).lt(sampledAt));
        long deactivated = mongoTemplate.updateMulti(gone, Update.update(ACTIVE, false), Schema.SNAPSHOTS)
                .getModifiedCount();
        return new Result(advanced.size(), reading.samples().size() - advanced.size(), deactivated, baseline);
    }

    /** Replaces the snapshot if it is older; returns the traffic since the replaced snapshot. */
    private Optional<TopicTraffic> advance(TopicSample sample, Instant sampledAt, boolean baseline) {
        Query older = Query.query(Criteria.where("_id").is(sample.topic()).and(COLLECTED_AT).lt(sampledAt));
        try {
            TopicSnapshot before = mongoTemplate.findAndReplace(older, snapshotOf(sample, sampledAt),
                    FindAndReplaceOptions.options().upsert(), TopicSnapshot.class, Schema.SNAPSHOTS,
                    TopicSnapshot.class);
            return Optional.of(TopicTraffic.between(before, sample, baseline));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    private void writeBuckets(String collection, String timeField, Instant bucket, List<TopicTraffic> traffic) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, collection);
        traffic.forEach(topic -> {
            TopicSample sample = topic.sample();
            bulk.upsert(Query.query(Criteria.where(TOPIC).is(sample.topic()).and(timeField).is(bucket)),
                    names(traffic(new Update(), topic), sample)
                            .set(BACKLOG_MSGS, sample.backlogMsgs())
                            .set(BACKLOG_BYTES, sample.backlogBytes()));
        });
        bulk.execute();
    }

    private void writeTotals(List<TopicTraffic> traffic, Instant sampledAt) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Schema.TOTALS);
        traffic.forEach(topic -> bulk.upsert(Query.query(Criteria.where("_id").is(topic.sample().topic())),
                names(traffic(new Update(), topic), topic.sample())
                        .min(FIRST_SEEN_AT, sampledAt)
                        .max(LAST_SEEN_AT, sampledAt)));
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

    /**
     * What one write did.
     *
     * @param advanced topics whose snapshot moved forward; their traffic was counted
     * @param skipped topics that already had a newer snapshot; nothing was counted for them
     * @param deactivated topics marked as gone
     * @param baseline {@code true} if this was the namespace's first collection
     */
    public record Result(int advanced, int skipped, long deactivated, boolean baseline) {
    }
}
