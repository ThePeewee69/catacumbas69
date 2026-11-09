package com.catacumbas69.catapulsar.service;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Calls Catametricas' API (ADR 0013), the only source of Pulsar statistics, at the URL held by
 * {@link CatametricasEndpoint}. If Catametricas cannot be reached or answers with a server error a
 * {@link CatametricasUnavailableException} is thrown, which the API maps to {@code 503} (RES-12).
 */
@Service
public class CatametricasClient {

    private static final ParameterizedTypeReference<List<TopicStats>> TOPICS = new ParameterizedTypeReference<>() {
    };

    private final RestClient catametricas;
    private final CatametricasEndpoint endpoint;

    /**
     * Creates the client.
     *
     * @param catametricas HTTP client for Catametricas (timeouts only; the URL comes from {@code endpoint})
     * @param endpoint Catametricas base URL, editable in the UI
     */
    public CatametricasClient(@Qualifier("catametricasRestClient") RestClient catametricas,
                              CatametricasEndpoint endpoint) {
        this.catametricas = catametricas;
        this.endpoint = endpoint;
    }

    /**
     * Lists the topics of a scope with their current state and all-time traffic.
     *
     * @param tenant tenant, or {@code null} for the whole cluster
     * @param namespace namespace without tenant, or {@code null}
     * @return one entry per topic or partition
     */
    public List<TopicStats> topics(String tenant, String namespace) {
        URI uri = scope("/api/v1/topics", tenant, namespace).encode().build().toUri();
        List<TopicStats> topics = get(uri, TOPICS);
        return topics == null ? List.of() : topics;
    }

    /**
     * Returns traffic and backlog over time, summed per group.
     *
     * @param tenant tenant, or {@code null}
     * @param namespace namespace without tenant, or {@code null}
     * @param groupBy {@code tenant}, {@code namespace} or {@code topic}
     * @param from start of the range
     * @param to end of the range
     * @param step bucket length, e.g. {@code 15m}
     * @return the series
     */
    public Series series(String tenant, String namespace, String groupBy, Instant from, Instant to, String step) {
        URI uri = scope("/api/v1/series", tenant, namespace)
                .queryParam("groupBy", groupBy)
                .queryParam("from", from.toString())
                .queryParam("to", to.toString())
                .queryParam("step", step)
                .encode().build().toUri();
        return get(uri, new ParameterizedTypeReference<Series>() {
        });
    }

    /**
     * Returns when the latest sample was taken and the interval in use.
     *
     * @return the collector state
     */
    /**
     * Reads one subscription's history ({@code GET /api/v1/subscriptions/series}, orch.md decision 77).
     *
     * @param tenant tenant
     * @param namespace namespace
     * @param topic local topic name without partition suffix
     * @param subscription subscription name
     * @param from start of the range
     * @param to end of the range
     * @param step bucket length, e.g. {@code 15m}
     * @return the series
     */
    public SubscriptionSeries subscriptionSeries(String tenant, String namespace, String topic, String subscription,
                                                 Instant from, Instant to, String step) {
        URI uri = scope("/api/v1/subscriptions/series", tenant, namespace)
                .queryParam("topic", topic)
                .queryParam("subscription", subscription)
                .queryParam("from", from.toString())
                .queryParam("to", to.toString())
                .queryParam("step", step)
                .encode().build().toUri();
        return get(uri, new ParameterizedTypeReference<SubscriptionSeries>() {
        });
    }

    public Collector collector() {
        return get(URI.create(endpoint.url() + "/api/v1/collector"), new ParameterizedTypeReference<Collector>() {
        });
    }

    /**
     * Asks Catametricas to collect a scope now and waits for it (forced collection, at most once per namespace
     * every 5 s; Catametricas audits it as {@code collection.forced}).
     *
     * @param tenant tenant, or {@code null} for the whole cluster
     * @param namespace namespace inside the tenant, or {@code null}
     * @param actor browser address, sent as {@code X-Forwarded-For} for the audit
     * @return what was collected
     * @throws CatametricasUnavailableException if Catametricas cannot be reached or fails
     */
    public ForcedCollection collect(String tenant, String namespace, String actor) {
        URI uri = scope("/api/v1/collect", tenant, namespace).encode().build().toUri();
        try {
            return catametricas.post().uri(uri).header(BridgeProxyService.FORWARDED_FOR, actor)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(ForcedCollection.class);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new CatametricasUnavailableException(e);
        }
    }

    private UriComponentsBuilder scope(String path, String tenant, String namespace) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(endpoint.url()).path(path);
        if (tenant != null) {
            uri.queryParam("tenant", tenant);
        }
        if (namespace != null) {
            uri.queryParam("namespace", namespace);
        }
        return uri;
    }

    private <T> T get(URI uri, ParameterizedTypeReference<T> type) {
        try {
            return catametricas.get().uri(uri).accept(MediaType.APPLICATION_JSON).retrieve().body(type);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new CatametricasUnavailableException(e);
        }
    }

    /**
     * Statistics of one topic or partition.
     *
     * @param topic full topic name
     * @param tenant tenant name
     * @param namespace namespace without tenant
     * @param name local name
     * @param baseName local name without partition suffix
     * @param current state at the last collection, or {@code null} if the topic no longer exists
     * @param total all-time traffic, or {@code null}
     */
    public record TopicStats(String topic, String tenant, String namespace, String name, String baseName,
                             Current current, Traffic total) {
    }

    /**
     * State of a topic at the last collection.
     *
     * @param collectedAt when it was read
     * @param backlogMsgs messages waiting
     * @param backlogBytes bytes waiting
     * @param msgRateIn published messages per second
     * @param msgRateOut delivered messages per second
     * @param throughputIn published bytes per second
     * @param throughputOut delivered bytes per second
     * @param storageSize bytes stored
     * @param subscriptions subscriptions
     * @param producers producers
     * @param consumers consumers
     */
    public record Current(Instant collectedAt, long backlogMsgs, long backlogBytes, double msgRateIn,
                          double msgRateOut, double throughputIn, double throughputOut, long storageSize,
                          int subscriptions, int producers, int consumers) {
    }

    /**
     * Messages and bytes in and out.
     *
     * @param msgsIn messages published
     * @param msgsOut messages delivered
     * @param bytesIn bytes published
     * @param bytesOut bytes delivered
     */
    public record Traffic(long msgsIn, long msgsOut, long bytesIn, long bytesOut) {
    }

    /**
     * Series summed per group.
     *
     * @param source where the values come from
     * @param groupBy what a group is
     * @param stepSeconds bucket length
     * @param times bucket starts, oldest first
     * @param groups one per group with data
     */
    public record Series(String source, String groupBy, long stepSeconds, List<Instant> times, List<Group> groups) {
    }

    /**
     * The series of one group, aligned with {@link Series#times()}.
     *
     * @param key group key
     * @param msgsIn messages published per bucket
     * @param msgsOut messages delivered per bucket
     * @param bytesIn bytes published per bucket
     * @param bytesOut bytes delivered per bucket
     * @param backlog backlog at the end of each bucket, {@code null} where nothing was collected
     */
    public record Group(String key, List<Long> msgsIn, List<Long> msgsOut, List<Long> bytesIn, List<Long> bytesOut,
                        List<Long> backlog) {
    }

    /**
     * State of the collection.
     *
     * @param lastCollectionAt latest sample, or {@code null}
     * @param intervalSeconds interval in use
     */
    public record Collector(Instant lastCollectionAt, Integer intervalSeconds) {
    }

    /**
     * One subscription's history, its partitions summed.
     *
     * @param source where the values come from
     * @param tenant tenant
     * @param namespace namespace
     * @param topic local topic name without partition suffix
     * @param subscription subscription name
     * @param stepSeconds bucket length
     * @param times bucket starts
     * @param backlog messages waiting at the last sample of each bucket ({@code null} where none)
     * @param backlogMax most messages waiting in each bucket
     * @param msgsOut messages delivered in each bucket
     * @param bytesOut bytes delivered in each bucket
     * @param unacked messages not acknowledged at the last sample of each bucket
     */
    public record SubscriptionSeries(String source, String tenant, String namespace, String topic,
                                     String subscription, long stepSeconds, List<Instant> times, List<Long> backlog,
                                     List<Long> backlogMax, List<Long> msgsOut, List<Long> bytesOut,
                                     List<Long> unacked) {
    }

    /**
     * Result of a forced collection.
     *
     * @param scope {@code cluster}, a tenant, or {@code tenant/namespace}
     * @param namespaces the namespaces of the scope
     * @param collected namespaces collected by this request
     * @param coalesced namespaces already collected by another request in the same 5 s window
     * @param failed namespaces that could not be collected
     * @param finishedAt when Catametricas finished
     * @param durationMillis how long it took
     */
    public record ForcedCollection(String scope, List<String> namespaces, int collected, int coalesced,
                                   List<String> failed, Instant finishedAt, long durationMillis) {
    }
}
