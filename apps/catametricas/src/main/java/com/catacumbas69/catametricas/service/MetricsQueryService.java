package com.catacumbas69.catametricas.service;

import static com.catacumbas69.catametricas.model.Schema.ACTIVE;
import static com.catacumbas69.catametricas.model.Schema.BACKLOG_MSGS;
import static com.catacumbas69.catametricas.model.Schema.BYTES_IN;
import static com.catacumbas69.catametricas.model.Schema.BYTES_OUT;
import static com.catacumbas69.catametricas.model.Schema.COLLECTED_AT;
import static com.catacumbas69.catametricas.model.Schema.MSGS_IN;
import static com.catacumbas69.catametricas.model.Schema.MSGS_OUT;
import static com.catacumbas69.catametricas.model.Schema.NAMESPACE;
import static com.catacumbas69.catametricas.model.Schema.TENANT;

import com.catacumbas69.catametricas.dto.CollectorResponse;
import com.catacumbas69.catametricas.dto.SeriesResponse;
import com.catacumbas69.catametricas.dto.TopicStatsResponse;
import com.catacumbas69.catametricas.dto.TopicStatsResponse.Current;
import com.catacumbas69.catametricas.dto.TopicStatsResponse.Traffic;
import com.catacumbas69.catametricas.model.Schema;
import com.catacumbas69.catametricas.model.TopicSnapshot;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Reads Catametricas' collections for the API (ADR 0013: today every range comes from MongoDB). Read-only;
 * history is summed per group and minute or hour by MongoDB, so a request reads at most one document per group
 * and minute or hour.
 */
@Service
public class MetricsQueryService {

    /** Source reported in series answered from MongoDB. */
    static final String SOURCE_MONGODB = "mongodb";

    private final MongoTemplate mongoTemplate;
    private final CollectInterval interval;

    /**
     * Creates the service.
     *
     * @param mongoTemplate template for Catametricas' collections
     * @param interval collection interval in use
     */
    public MetricsQueryService(MongoTemplate mongoTemplate, CollectInterval interval) {
        this.mongoTemplate = mongoTemplate;
        this.interval = interval;
    }

    /**
     * Lists the topics of a scope with their current state and all-time traffic.
     *
     * @param tenant tenant, or {@code null} for every tenant
     * @param namespace namespace without tenant, or {@code null} for every namespace of the tenant
     * @return one entry per topic or partition, by name; topics that no longer exist keep their totals and have
     *         no current state
     */
    public List<TopicStatsResponse> topics(String tenant, String namespace) {
        if (namespace != null && tenant == null) {
            throw new InvalidQueryException("namespace needs tenant");
        }
        Map<String, TopicStatsResponse> topics = new TreeMap<>();
        Query active = Query.query(scope(tenant, namespace).and(ACTIVE).is(true));
        for (TopicSnapshot snapshot : mongoTemplate.find(active, TopicSnapshot.class, Schema.SNAPSHOTS)) {
            topics.put(snapshot.topic(), new TopicStatsResponse(snapshot.topic(), snapshot.tenant(),
                    snapshot.namespace(), snapshot.name(), snapshot.baseName(), currentOf(snapshot), null));
        }
        for (Document total : mongoTemplate.find(Query.query(scope(tenant, namespace)), Document.class,
                Schema.TOTALS)) {
            String topic = total.getString("_id");
            Traffic traffic = new Traffic(number(total, MSGS_IN), number(total, MSGS_OUT), number(total, BYTES_IN),
                    number(total, BYTES_OUT));
            TopicStatsResponse known = topics.get(topic);
            topics.put(topic, known == null
                    ? new TopicStatsResponse(topic, total.getString(TENANT), total.getString(NAMESPACE),
                            localName(topic), total.getString(Schema.BASE_NAME), null, traffic)
                    : new TopicStatsResponse(known.topic(), known.tenant(), known.namespace(), known.name(),
                            known.baseName(), known.current(), traffic));
        }
        return List.copyOf(topics.values());
    }

    /**
     * Returns traffic and backlog over time, summed per group.
     *
     * @param query validated request
     * @return the series
     */
    public SeriesResponse series(SeriesQuery query) {
        String key = query.groupBy().field();
        String time = query.timeField();
        Aggregation history = Aggregation.newAggregation(
                Aggregation.match(scope(query.tenant(), query.namespace())
                        .and(time).gte(query.times().get(0)).lt(query.end())),
                Aggregation.group(key, time)
                        .sum(MSGS_IN).as(MSGS_IN)
                        .sum(MSGS_OUT).as(MSGS_OUT)
                        .sum(BYTES_IN).as(BYTES_IN)
                        .sum(BYTES_OUT).as(BYTES_OUT)
                        .sum(BACKLOG_MSGS).as(BACKLOG_MSGS));
        SeriesAssembler assembler = new SeriesAssembler(query.times(), query.step());
        mongoTemplate.aggregate(history, query.collection(), Document.class).forEach(document -> {
            Document id = document.get("_id", Document.class);
            assembler.add(id.getString(key), instant(id.get(time)), number(document, MSGS_IN),
                    number(document, MSGS_OUT), number(document, BYTES_IN), number(document, BYTES_OUT),
                    number(document, BACKLOG_MSGS));
        });
        return new SeriesResponse(SOURCE_MONGODB, query.groupBy().value(), query.step().toSeconds(), query.times(),
                assembler.groups());
    }

    /**
     * Returns when the latest sample was taken and the interval in use.
     *
     * @return the collector state
     */
    public CollectorResponse collector() {
        Query latest = new Query().with(Sort.by(Sort.Direction.DESC, COLLECTED_AT)).limit(1);
        latest.fields().include(COLLECTED_AT);
        Document newest = mongoTemplate.findOne(latest, Document.class, Schema.SNAPSHOTS);
        return new CollectorResponse(newest == null ? null : instant(newest.get(COLLECTED_AT)), interval.seconds());
    }

    private static Criteria scope(String tenant, String namespace) {
        Criteria criteria = new Criteria();
        if (tenant != null) {
            criteria = criteria.and(TENANT).is(tenant);
        }
        if (namespace != null) {
            criteria = criteria.and(NAMESPACE).is(namespace);
        }
        return criteria;
    }

    private static Current currentOf(TopicSnapshot snapshot) {
        return new Current(snapshot.collectedAt(), snapshot.backlogMsgs(), snapshot.backlogBytes(),
                snapshot.msgRateIn(), snapshot.msgRateOut(), snapshot.throughputIn(), snapshot.throughputOut(),
                snapshot.storageSize(), snapshot.subscriptions(), snapshot.producers(), snapshot.consumers());
    }

    private static String localName(String topic) {
        return topic == null ? null : topic.substring(topic.lastIndexOf('/') + 1);
    }

    static long number(Document document, String field) {
        Object value = document.get(field);
        return value instanceof Number number ? number.longValue() : 0;
    }

    static Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return value instanceof Instant instant ? instant : null;
    }
}
