package com.catacumbas69.catametricas.controller;

import com.catacumbas69.catametricas.dto.CollectorResponse;
import com.catacumbas69.catametricas.dto.SeriesResponse;
import com.catacumbas69.catametricas.dto.TopicStatsResponse;
import com.catacumbas69.catametricas.service.MetricsQueryService;
import com.catacumbas69.catametricas.service.SeriesQuery;
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
 * Catametricas' query API (ADR 0013): the only way other services read Pulsar statistics. Read-only.
 */
@Tag(name = "Metrics")
@RestController
@RequestMapping("/api/v1")
public class MetricsController {

    /** Tenant and namespace names, as Pulsar allows them. */
    static final String NAME = "^[A-Za-z0-9][A-Za-z0-9._=:-]*$";

    private final MetricsQueryService metrics;
    private final Clock clock;

    /**
     * Creates the controller.
     *
     * @param metrics reads the statistics
     * @param clock time source, for the default end of a range
     */
    public MetricsController(MetricsQueryService metrics, Clock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Lists topics with their current state and all-time traffic.
     *
     * @param tenant only this tenant
     * @param namespace only this namespace of {@code tenant}
     * @return one entry per topic or partition, by name
     */
    @Operation(summary = "Topics (or partitions) with their current state and all-time traffic")
    @GetMapping("/topics")
    public List<TopicStatsResponse> topics(
            @RequestParam(required = false) @Pattern(regexp = NAME) String tenant,
            @RequestParam(required = false) @Pattern(regexp = NAME) String namespace) {
        return metrics.topics(tenant, namespace);
    }

    /**
     * Returns traffic and backlog over time, summed per tenant, namespace or topic.
     *
     * @param tenant only this tenant
     * @param namespace only this namespace of {@code tenant}
     * @param groupBy {@code tenant}, {@code namespace} (needs {@code tenant}) or {@code topic} (needs
     *                {@code namespace})
     * @param from start of the range, ISO-8601
     * @param to end of the range, ISO-8601 (default now)
     * @param step bucket length: {@code 1m}–{@code 59m} read per-minute history (30 days), whole hours
     *             ({@code 1h}, {@code 6h}) read hourly history
     * @return the series; at most 2000 buckets
     */
    @Operation(summary = "Traffic and backlog over time, summed per tenant, namespace or topic")
    @GetMapping("/series")
    public SeriesResponse series(
            @RequestParam(required = false) @Pattern(regexp = NAME) String tenant,
            @RequestParam(required = false) @Pattern(regexp = NAME) String namespace,
            @RequestParam String groupBy,
            @RequestParam Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam String step) {
        return metrics.series(SeriesQuery.of(tenant, namespace, groupBy, from, to == null ? clock.instant() : to,
                step));
    }

    /**
     * Returns when the latest sample was taken and the interval in use.
     *
     * @return the collector state
     */
    @Operation(summary = "Latest collection and interval in use")
    @GetMapping("/collector")
    public CollectorResponse collector() {
        return metrics.collector();
    }
}
