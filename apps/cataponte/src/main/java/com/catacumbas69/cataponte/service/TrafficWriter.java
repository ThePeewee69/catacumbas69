package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.BridgeTrafficMinute;
import com.catacumbas69.cataponte.model.BridgeTrafficTotal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Writes drained traffic counts with two unordered bulk upserts: one per minute bucket into
 * {@code bridge_traffic_minutes}, one per topic into {@code bridge_traffic_totals}. Counts are added
 * with {@code $inc}, so repeated writes for the same minute accumulate correctly.
 */
@Component
public class TrafficWriter {

    private static final String BRIDGE_ID = "bridgeId";
    private static final String ROLE = "role";
    private static final String TOPIC = "topic";
    private static final String MESSAGES = "messages";
    private static final String BYTES = "bytes";

    private final MongoTemplate mongoTemplate;

    /**
     * Creates the writer.
     *
     * @param mongoTemplate template for bulk operations
     */
    public TrafficWriter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Stores the counts. Throws if MongoDB rejects the write; the caller keeps the counts and retries.
     *
     * @param drained counts per series and minute
     */
    public void write(Collection<TopicTraffic.Drained> drained) {
        BulkOperations minutes = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, BridgeTrafficMinute.class);
        drained.forEach(entry -> minutes.upsert(minuteQuery(entry), new Update()
                .inc(MESSAGES, entry.messages())
                .inc(BYTES, entry.bytes())
                .set("bridgeName", entry.series().bridgeName())));
        minutes.execute();

        BulkOperations totals = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, BridgeTrafficTotal.class);
        sumPerSeries(drained).forEach((series, sum) -> totals.upsert(seriesQuery(series), new Update()
                .inc(MESSAGES, sum.messages())
                .inc(BYTES, sum.bytes())
                .set("bridgeName", series.bridgeName())
                .min("firstAt", sum.firstMinute())
                .max("lastAt", sum.lastMinute())));
        totals.execute();
    }

    private static Query minuteQuery(TopicTraffic.Drained entry) {
        return Query.query(Criteria.where(BRIDGE_ID).is(entry.series().bridgeId())
                .and(ROLE).is(entry.series().role().name())
                .and(TOPIC).is(entry.series().topic())
                .and("minute").is(Instant.ofEpochMilli(entry.minuteStartMillis())));
    }

    private static Query seriesQuery(TopicTraffic series) {
        return Query.query(Criteria.where(BRIDGE_ID).is(series.bridgeId())
                .and(ROLE).is(series.role().name())
                .and(TOPIC).is(series.topic()));
    }

    private static Map<TopicTraffic, Sum> sumPerSeries(Collection<TopicTraffic.Drained> drained) {
        Map<TopicTraffic, Sum> sums = new LinkedHashMap<>();
        drained.forEach(entry -> sums.merge(entry.series(), Sum.of(entry), Sum::plus));
        return sums;
    }

    private record Sum(long messages, long bytes, Instant firstMinute, Instant lastMinute) {

        static Sum of(TopicTraffic.Drained entry) {
            Instant minute = Instant.ofEpochMilli(entry.minuteStartMillis());
            return new Sum(entry.messages(), entry.bytes(), minute, minute);
        }

        Sum plus(Sum other) {
            return new Sum(messages + other.messages, bytes + other.bytes,
                    firstMinute.isBefore(other.firstMinute) ? firstMinute : other.firstMinute,
                    lastMinute.isAfter(other.lastMinute) ? lastMinute : other.lastMinute);
        }
    }
}
