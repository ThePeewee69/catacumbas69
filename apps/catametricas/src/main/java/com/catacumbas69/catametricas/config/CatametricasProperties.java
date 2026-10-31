package com.catacumbas69.catametricas.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration under the {@code catametricas.*} prefix (CFG-1).
 *
 * @param pulsar Pulsar admin client settings
 * @param collect collection settings
 * @param mongo MongoDB client timeouts
 */
@Validated
@ConfigurationProperties(prefix = "catametricas")
public record CatametricasProperties(
        @Valid @NotNull Pulsar pulsar,
        @Valid @NotNull Collect collect,
        @Valid @NotNull Mongo mongo) {

    /** Smallest accepted collection interval, in seconds. */
    public static final int MIN_INTERVAL_SECONDS = 15;

    /** Largest accepted collection interval, in seconds. */
    public static final int MAX_INTERVAL_SECONDS = 600;

    /**
     * Pulsar admin client settings (RES-2).
     *
     * @param serviceUrl admin HTTP URL, e.g. {@code http://host:8080}
     * @param connectTimeout HTTP connect timeout
     * @param readTimeout HTTP read timeout
     * @param requestTimeout HTTP total request timeout
     */
    public record Pulsar(
            @NotBlank String serviceUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @NotNull Duration requestTimeout) {
    }

    /**
     * Collection settings.
     *
     * @param defaultIntervalSeconds interval used when {@code pulsario_settings} has no valid value
     * @param initialDelay wait before the first collection after start-up
     * @param concurrency parallel topic-stats calls per collection
     * @param minuteRetention how long per-minute history is kept (TTL index)
     * @param skipSystemTopics ignore Pulsar system topics (local name starts with {@code __})
     */
    public record Collect(
            @Min(MIN_INTERVAL_SECONDS) @Max(MAX_INTERVAL_SECONDS) int defaultIntervalSeconds,
            @NotNull Duration initialDelay,
            @Min(1) @Max(64) int concurrency,
            @NotNull Duration minuteRetention,
            boolean skipSystemTopics) {
    }

    /**
     * MongoDB client timeouts (RES-2).
     *
     * @param serverSelectionTimeout how long an operation waits for a usable server
     * @param connectTimeout socket connect timeout
     * @param readTimeout socket read timeout
     */
    public record Mongo(
            @NotNull Duration serverSelectionTimeout,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {
    }
}
