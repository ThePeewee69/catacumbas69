package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.dto.OverviewResponse;
import com.catacumbas69.catapulsar.service.CatametricasClient.Collector;
import com.catacumbas69.catapulsar.service.CatametricasClient.Series;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Builds the cluster, tenant and namespace overviews from Catametricas' API (ADR 0013): the topics of the scope
 * (current state and all-time traffic), the series of the period summed per row, and when Catametricas last
 * collected. Catapulsar keeps the presentation (rows, summary, chart buckets); Catametricas owns the data. If
 * Catametricas is not available a {@link CatametricasUnavailableException} is thrown ({@code 503}).
 */
@Service
public class OverviewService {

    private final CatametricasClient catametricas;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param catametricas client for Catametricas' API
     * @param clock time source
     */
    public OverviewService(CatametricasClient catametricas, Clock clock) {
        this.catametricas = catametricas;
        this.clock = clock;
    }

    /**
     * Overview of the whole cluster, one row per tenant.
     *
     * @param period time range
     * @return the overview
     */
    public OverviewResponse cluster(OverviewPeriod period) {
        return build(OverviewScope.CLUSTER, null, null, period);
    }

    /**
     * Overview of a tenant, one row per namespace.
     *
     * @param tenant tenant name
     * @param period time range
     * @return the overview
     */
    public OverviewResponse tenant(String tenant, OverviewPeriod period) {
        return build(OverviewScope.TENANT, tenant, null, period);
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
        return build(OverviewScope.NAMESPACE, tenant, namespace, period);
    }

    /**
     * Has Catametricas collect a scope now, so the next overview shows this instant (forced collection).
     *
     * @param tenant tenant, or {@code null} for the cluster
     * @param namespace namespace inside the tenant, or {@code null}
     * @param actor browser address, for Catametricas' audit
     * @return what was collected
     */
    public CatametricasClient.ForcedCollection collect(String tenant, String namespace, String actor) {
        return catametricas.collect(tenant, namespace, actor);
    }

    private OverviewResponse build(OverviewScope scope, String tenant, String namespace, OverviewPeriod period) {
        Instant now = clock.instant();
        List<Instant> buckets = period.buckets(now);
        OverviewAssembler assembler = new OverviewAssembler(scope, buckets);

        catametricas.topics(tenant, namespace).forEach(assembler::addTopic);
        Series series = catametricas.series(tenant, namespace, scope.groupBy(), buckets.get(0), now,
                period.stepParameter());
        if (series != null && series.groups() != null) {
            series.groups().forEach(group -> assembler.addSeries(series.times(), group));
        }
        Collector collector = catametricas.collector();
        return new OverviewResponse(scope.value(), tenant, namespace, period.value(), period.step().toSeconds(),
                buckets, collector == null ? null : collector.lastCollectionAt(),
                collector == null ? null : collector.intervalSeconds(), assembler.summary(), assembler.rows());
    }
}
