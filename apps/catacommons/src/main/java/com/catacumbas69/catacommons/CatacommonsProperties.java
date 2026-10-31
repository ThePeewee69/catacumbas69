package com.catacumbas69.catacommons;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration under {@code catacommons.*}. Only {@code catacommons.app} must be set; everything else
 * has a default.
 *
 * @param app this app's name, e.g. {@code catapulsar}: its namespace is {@code catacumbas69/<app>} and its
 *            events have source {@code /catacumbas69/<app>}
 * @param events event publishing
 * @param provisioning start-up creation of the tenant, namespace and topics
 */
@Validated
@ConfigurationProperties(prefix = "catacommons")
public record CatacommonsProperties(
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]*") String app,
        @Valid @NotNull @DefaultValue Events events,
        @Valid @NotNull @DefaultValue Provisioning provisioning) {

    /**
     * Event publishing (ADR 0008).
     *
     * @param enabled {@code false} turns publishing off (events are dropped silently), e.g. in tests
     * @param retryDelays wait before each retry; three retries by default
     * @param sendTimeout how long one attempt may take
     */
    public record Events(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue({"1s", "2s", "4s"}) List<Duration> retryDelays,
            @NotNull @DefaultValue("10s") Duration sendTimeout) {
    }

    /**
     * Start-up provisioning (ADR 0008). Runs in the background and is retried until it succeeds.
     *
     * @param enabled {@code false} skips it (the namespace must then exist)
     * @param retryInterval first wait after a failed attempt; doubled after each failure
     * @param maxRetryInterval upper bound for the wait
     * @param eventsRetentionTime how long event messages are kept, consumed or not
     * @param eventsRetentionSize retention size limit of each topic in the namespace
     * @param eventsBacklogQuota unconsumed backlog allowed per topic; above it publishing fails
     *                           ({@code producer_exception})
     * @param compactionThreshold backlog size that triggers automatic compaction of compacted topics
     */
    public record Provisioning(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("30s") Duration retryInterval,
            @NotNull @DefaultValue("5m") Duration maxRetryInterval,
            @NotNull @DefaultValue("7d") Duration eventsRetentionTime,
            @NotNull @DefaultValue("1GB") DataSize eventsRetentionSize,
            @NotNull @DefaultValue("1GB") DataSize eventsBacklogQuota,
            @NotNull @DefaultValue("1MB") DataSize compactionThreshold) {
    }
}
