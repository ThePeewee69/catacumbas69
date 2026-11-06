package com.catacumbas69.catametricas.service;

import static com.catacumbas69.catametricas.model.Schema.ACTIVE;
import static com.catacumbas69.catametricas.model.Schema.BASE_NAME;
import static com.catacumbas69.catametricas.model.Schema.BYTES_OUT;
import static com.catacumbas69.catametricas.model.Schema.MSGS_OUT;
import static com.catacumbas69.catametricas.model.Schema.NAMESPACE;
import static com.catacumbas69.catametricas.model.Schema.SUBS;
import static com.catacumbas69.catametricas.model.Schema.SUB_BACKLOG;
import static com.catacumbas69.catametricas.model.Schema.SUB_BACKLOG_MAX;
import static com.catacumbas69.catametricas.model.Schema.SUB_UNACKED;
import static com.catacumbas69.catametricas.model.Schema.TENANT;

import com.catacumbas69.catametricas.dto.SubscriptionSeriesResponse;
import com.catacumbas69.catametricas.dto.SubscriptionStatsResponse;
import com.catacumbas69.catametricas.model.Schema;
import com.catacumbas69.catametricas.model.SubscriptionSnapshot;
import com.catacumbas69.catametricas.model.TopicSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Reads the subscriptions of Catametricas' snapshots and history (orch.md decision 77). Read-only. A topic's
 * partitions are separate documents; the series sums them per minute or hour first.
 */
@Service
public class SubscriptionQueryService {

    private static final Comparator<SubscriptionStatsResponse> ORDER =
            Comparator.comparing(SubscriptionStatsResponse::topic)
                    .thenComparing(SubscriptionStatsResponse::subscription);

    private final MongoTemplate mongoTemplate;

    /**
     * Creates the service.
     *
     * @param mongoTemplate template for Catametricas' collections
     */
    public SubscriptionQueryService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Lists the subscriptions of the active topics of a scope, as last collected.
     *
     * @param tenant tenant, or {@code null} for every tenant
     * @param namespace namespace without tenant, or {@code null}
     * @param topic local topic name without partition suffix, or {@code null} (needs {@code namespace})
     * @return one entry per topic or partition and subscription, by topic and subscription
     */
    public List<SubscriptionStatsResponse> subscriptions(String tenant, String namespace, String topic) {
        if (namespace != null && tenant == null) {
            throw new InvalidQueryException("namespace needs tenant");
        }
        if (topic != null && namespace == null) {
            throw new InvalidQueryException("topic needs tenant and namespace");
        }
        Criteria criteria = scope(tenant, namespace).and(ACTIVE).is(true);
        if (topic != null) {
            criteria = criteria.and(BASE_NAME).is(topic);
        }
        List<SubscriptionStatsResponse> result = new ArrayList<>();
        for (TopicSnapshot snapshot : mongoTemplate.find(Query.query(criteria), TopicSnapshot.class,
                Schema.SNAPSHOTS)) {
            if (snapshot.subs() == null) {
                continue;
            }
            for (SubscriptionSnapshot sub : snapshot.subs()) {
                result.add(new SubscriptionStatsResponse(snapshot.topic(), snapshot.tenant(), snapshot.namespace(),
                        snapshot.name(), snapshot.baseName(), sub.name(), sub.type(), snapshot.collectedAt(),
                        sub.backlog(), sub.unacked(), sub.consumers(), sub.msgRateOut()));
            }
        }
        result.sort(ORDER);
        return result;
    }

    /**
     * Returns one subscription's backlog and deliveries over time, its partitions summed.
     *
     * @param query validated range (tenant, namespace, buckets); its {@code groupBy} is ignored
     * @param topic local topic name without partition suffix
     * @param subscription subscription name
     * @return the series
     */
    public SubscriptionSeriesResponse series(SeriesQuery query, String topic, String subscription) {
        String entry = SUBS + "." + Schema.subscriptionKey(subscription);
        String time = query.timeField();
        Query history = Query.query(scope(query.tenant(), query.namespace()).and(BASE_NAME).is(topic)
                .and(time).gte(query.times().get(0)).lt(query.end())
                .and(entry).exists(true));
        history.fields().include(time).include(entry);

        // Partitions first: one sum per minute or hour.
        Map<Instant, long[]> perTime = new TreeMap<>();
        for (Document document : mongoTemplate.find(history, Document.class, query.collection())) {
            Instant at = MetricsQueryService.instant(document.get(time));
            Document subs = document.get(SUBS, Document.class);
            Document values = subs == null ? null : subs.get(Schema.subscriptionKey(subscription), Document.class);
            if (at == null || values == null) {
                continue;
            }
            long[] sum = perTime.computeIfAbsent(at, ignored -> new long[5]);
            sum[0] += MetricsQueryService.number(values, SUB_BACKLOG);
            sum[1] += MetricsQueryService.number(values, SUB_BACKLOG_MAX);
            sum[2] += MetricsQueryService.number(values, MSGS_OUT);
            sum[3] += MetricsQueryService.number(values, BYTES_OUT);
            sum[4] += MetricsQueryService.number(values, SUB_UNACKED);
        }

        int buckets = query.times().size();
        Long[] backlog = new Long[buckets];
        Long[] backlogMax = new Long[buckets];
        Long[] unacked = new Long[buckets];
        long[] msgsOut = new long[buckets];
        long[] bytesOut = new long[buckets];
        long first = query.times().get(0).toEpochMilli();
        long step = query.step().toMillis();
        // Oldest first, so the last sample of a bucket is the one kept.
        perTime.forEach((at, sum) -> {
            long index = Math.floorDiv(at.toEpochMilli() - first, step);
            if (index < 0 || index >= buckets) {
                return;
            }
            int i = (int) index;
            backlog[i] = sum[0];
            backlogMax[i] = backlogMax[i] == null ? sum[1] : Math.max(backlogMax[i], sum[1]);
            msgsOut[i] += sum[2];
            bytesOut[i] += sum[3];
            unacked[i] = sum[4];
        });
        return new SubscriptionSeriesResponse(MetricsQueryService.SOURCE_MONGODB, query.tenant(), query.namespace(),
                topic, subscription, query.step().toSeconds(), query.times(), Arrays.asList(backlog),
                Arrays.asList(backlogMax), Arrays.stream(msgsOut).boxed().toList(),
                Arrays.stream(bytesOut).boxed().toList(), Arrays.asList(unacked));
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
}
