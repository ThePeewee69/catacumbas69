package com.catacumbas69.catametricas.controller;

import com.catacumbas69.catametricas.dto.SubscriptionSeriesResponse;
import com.catacumbas69.catametricas.dto.SubscriptionStatsResponse;
import com.catacumbas69.catametricas.service.SeriesQuery;
import com.catacumbas69.catametricas.service.SubscriptionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscriptions as Catametricas collects them (orch.md decision 77): their state at the last collection, and
 * their history. Read-only; used by Catavigia's backlog and poison checks and by Catapulsar's subscription chart.
 */
@Tag(name = "Subscriptions")
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionsController {

    /** Topic and subscription names: anything without spaces or slashes. */
    static final String LOCAL_NAME = "^[^\\s/]{1,255}$";

    private final SubscriptionQueryService subscriptions;
    private final Clock clock;

    /**
     * Creates the controller.
     *
     * @param subscriptions reads the subscriptions
     * @param clock time source, for the default end of a range
     */
    public SubscriptionsController(SubscriptionQueryService subscriptions, Clock clock) {
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    /**
     * Lists subscriptions with their state at the last collection.
     *
     * @param tenant only this tenant
     * @param namespace only this namespace of {@code tenant}
     * @param topic only this topic of {@code namespace} (local name without partition suffix)
     * @return one entry per topic or partition and subscription
     */
    @Operation(summary = "Subscriptions of the active topics, as last collected")
    @GetMapping
    public List<SubscriptionStatsResponse> list(
            @RequestParam(required = false) @Pattern(regexp = MetricsController.NAME) String tenant,
            @RequestParam(required = false) @Pattern(regexp = MetricsController.NAME) String namespace,
            @RequestParam(required = false) @Pattern(regexp = LOCAL_NAME) String topic) {
        return subscriptions.subscriptions(tenant, namespace, topic);
    }

    /**
     * Returns one subscription's backlog and deliveries over time, its partitions summed.
     *
     * @param tenant tenant
     * @param namespace namespace of {@code tenant}
     * @param topic local topic name without partition suffix
     * @param subscription subscription name
     * @param from start of the range, ISO-8601
     * @param to end of the range, ISO-8601 (default now)
     * @param step bucket length, as for {@code /api/v1/series}
     * @return the series; at most 2000 buckets
     */
    @Operation(summary = "History of one subscription: backlog, its maximum, deliveries and unacked")
    @GetMapping("/series")
    public SubscriptionSeriesResponse series(
            @RequestParam @Pattern(regexp = MetricsController.NAME) String tenant,
            @RequestParam @Pattern(regexp = MetricsController.NAME) String namespace,
            @RequestParam @Pattern(regexp = LOCAL_NAME) String topic,
            @RequestParam @Pattern(regexp = LOCAL_NAME) String subscription,
            @RequestParam Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam String step) {
        SeriesQuery query = SeriesQuery.of(tenant, namespace, "topic", from, to == null ? clock.instant() : to, step);
        return subscriptions.series(query, topic, subscription);
    }
}
