package com.catacumbas69.catametricas.service;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catametricas.config.CatametricasProperties;
import com.catacumbas69.catametricas.dto.ForcedCollectionResponse;
import com.catacumbas69.catametricas.model.CollectTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Collects a scope now, for the overviews' "Collect now" button and live mode. Each namespace of the scope is run
 * as a task with the id {@code forced:<namespace>@<window start>} through {@link StatsCollector}, so:
 *
 * <ul>
 *   <li>a namespace is collected at most once per window ({@code catametricas.collect.forced-window}, 5 s), by
 *       whichever request or instance claims it first; the others count it as coalesced;</li>
 *   <li>the write is the same idempotent write as a scheduled task, so traffic is never counted twice and the
 *       history stays consistent.</li>
 * </ul>
 *
 * <p>Every request is audited as {@code collection.forced} (ADR 0008), with the caller as actor.
 */
@Service
public class ForcedCollection {

    private static final Logger LOG = LoggerFactory.getLogger(ForcedCollection.class);
    private static final String ENTITY = "collection";
    private static final String CLUSTER = "cluster";

    private final StatsCollector collector;
    private final PulsarStatsReader reader;
    private final CollectInterval interval;
    private final EventPublisher events;
    private final Clock clock;
    private final long windowSeconds;

    /**
     * Creates the service.
     *
     * @param collector runs one namespace
     * @param reader lists the namespaces of a scope
     * @param interval collection interval in use
     * @param events publishes {@code collection.forced}
     * @param clock time source
     * @param properties forced-collection window
     */
    public ForcedCollection(StatsCollector collector, PulsarStatsReader reader, CollectInterval interval,
                            EventPublisher events, Clock clock, CatametricasProperties properties) {
        this.collector = collector;
        this.reader = reader;
        this.interval = interval;
        this.events = events;
        this.clock = clock;
        this.windowSeconds = Math.max(1, properties.collect().forcedWindow().toSeconds());
    }

    /**
     * Collects every namespace of a scope, one after the other, and waits for them.
     *
     * @param tenant tenant, or {@code null} for the whole cluster
     * @param namespace namespace inside the tenant, or {@code null} for the whole tenant
     * @param actor who asked: client address
     * @return what was collected
     * @throws InvalidQueryException if a namespace is given without a tenant
     * @throws PulsarAdminException if the namespaces of the scope cannot be listed
     */
    public ForcedCollectionResponse collect(String tenant, String namespace, String actor)
            throws PulsarAdminException {
        if (namespace != null && tenant == null) {
            throw new InvalidQueryException("namespace needs tenant");
        }
        String scope = namespace != null ? tenant + "/" + namespace : tenant != null ? tenant : CLUSTER;
        Instant started = clock.instant();
        Instant window = Instant.ofEpochSecond(started.getEpochSecond() - started.getEpochSecond() % windowSeconds);
        List<String> namespaces = namespacesOf(tenant, namespace);
        int seconds = interval.seconds();
        int collected = 0;
        int coalesced = 0;
        List<String> failed = new ArrayList<>();
        for (String name : namespaces) {
            switch (collector.handle(CollectTask.forced(name, window, seconds))) {
                case DONE -> collected++;
                case DUPLICATE -> coalesced++;
                default -> failed.add(name);
            }
        }
        Instant finished = clock.instant();
        ForcedCollectionResponse response = new ForcedCollectionResponse(scope, namespaces, collected, coalesced,
                List.copyOf(failed), finished, Duration.between(started, finished).toMillis());
        audit(response, actor);
        return response;
    }

    private List<String> namespacesOf(String tenant, String namespace) throws PulsarAdminException {
        if (namespace != null) {
            return List.of(tenant + "/" + namespace);
        }
        List<String> all = reader.namespaces();
        return tenant == null ? all : all.stream().filter(name -> name.startsWith(tenant + "/")).toList();
    }

    private void audit(ForcedCollectionResponse response, String actor) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("namespaces", response.namespaces().size());
        details.put("collected", response.collected());
        details.put("coalesced", response.coalesced());
        details.put("failed", String.join(",", response.failed()));
        details.put("durationMillis", response.durationMillis());
        try {
            events.publish(ENTITY, "forced", response.scope(), EventData.action(actor, details));
        } catch (RuntimeException e) {
            LOG.warn("Event collection.forced not published: {}", e.getMessage());
        }
    }
}
