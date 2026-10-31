package com.catacumbas69.catapulsar.service;

import static com.catacumbas69.catapulsar.model.CatametricasSchema.ACTIVE;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.BACKLOG_MSGS;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.BASE_NAME;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.BYTES_IN;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.BYTES_OUT;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.MSGS_IN;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.MSGS_OUT;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.NAMESPACE;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.TENANT;

import com.catacumbas69.catapulsar.dto.OverviewResponse;
import com.catacumbas69.catapulsar.model.CatametricasSchema;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Builds the cluster, tenant and namespace overviews from the statistics Catametricas writes to MongoDB
 * (ADR 0007). Read-only: Catapulsar never writes Catametricas' collections. History is summed per row and time
 * bucket by MongoDB, so a request reads at most one document per row and minute or hour.
 */
@Service
public class OverviewService {

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param mongoTemplate template for Catametricas' collections
     * @param clock time source
     */
    public OverviewService(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /**
     * Overview of the whole cluster, one row per tenant.
     *
     * @param period time range
     * @return the overview
     */
    public OverviewResponse cluster(OverviewPeriod period) {
        return build("cluster", null, null, TENANT, new Criteria(), period);
    }

    /**
     * Overview of a tenant, one row per namespace.
     *
     * @param tenant tenant name
     * @param period time range
     * @return the overview
     */
    public OverviewResponse tenant(String tenant, OverviewPeriod period) {
        return build("tenant", tenant, null, NAMESPACE, Criteria.where(TENANT).is(tenant), period);
    }

    /**
     * Overview of a namespace, one row per topic (partitions summed into their topic).
     *
     * @param tenant tenant name
     * @param namespace namespace name without the tenant
     * @param period time range
     * @return the overview
     */
    public OverviewResponse namespace(String tenant, String namespace, OverviewPeriod period) {
        return build("namespace", tenant, namespace, BASE_NAME,
                Criteria.where(TENANT).is(tenant).and(NAMESPACE).is(namespace), period);
    }

    private OverviewResponse build(String scope, String tenant, String namespace, String keyField, Criteria where,
                                   OverviewPeriod period) {
        List<Instant> buckets = period.buckets(clock.instant());
        OverviewAssembler assembler = new OverviewAssembler(keyField, buckets, period.step());

        Query activeTopics = Query.query(copy(where).and(ACTIVE).is(true));
        mongoTemplate.find(activeTopics, Document.class, CatametricasSchema.SNAPSHOTS).forEach(assembler::addSnapshot);
        mongoTemplate.find(Query.query(copy(where)), Document.class, CatametricasSchema.TOTALS)
                .forEach(assembler::addTotal);

        Aggregation history = Aggregation.newAggregation(
                Aggregation.match(copy(where).and(period.timeField()).gte(buckets.get(0))),
                Aggregation.group(keyField, period.timeField())
                        .sum(MSGS_IN).as(MSGS_IN)
                        .sum(MSGS_OUT).as(MSGS_OUT)
                        .sum(BYTES_IN).as(BYTES_IN)
                        .sum(BYTES_OUT).as(BYTES_OUT)
                        .sum(BACKLOG_MSGS).as(BACKLOG_MSGS));
        mongoTemplate.aggregate(history, period.collection(), Document.class)
                .forEach(document -> assembler.addHistory(document, period.timeField()));

        Document status = mongoTemplate.findById(CatametricasSchema.STATUS_ID, Document.class,
                CatametricasSchema.STATUS);
        Instant collectedAt = status == null ? null : OverviewAssembler.instant(status.get("finishedAt"));
        Integer interval = status == null || !(status.get("intervalSeconds") instanceof Number number)
                ? null : number.intValue();
        return new OverviewResponse(scope, tenant, namespace, period.value(), period.step().toSeconds(), buckets,
                collectedAt, interval, assembler.summary(), assembler.rows());
    }

    /** Criteria are mutable; every query gets its own copy of the scope. */
    private static Criteria copy(Criteria where) {
        Criteria copy = new Criteria();
        where.getCriteriaObject().forEach((field, value) -> copy.and(field).is(value));
        return copy;
    }
}
