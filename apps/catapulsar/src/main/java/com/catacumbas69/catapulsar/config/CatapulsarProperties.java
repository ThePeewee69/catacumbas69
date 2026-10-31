package com.catacumbas69.catapulsar.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration under the {@code catapulsar.*} prefix (CFG-1). Defaults live in
 * {@code application.properties}; environment variables override them (ARCH-8).
 *
 * @param pulsar Pulsar admin and client settings
 * @param cataponte Cataponte HTTP client settings
 * @param mongo MongoDB client timeouts
 * @param ui web UI defaults
 * @param catametricas Catametricas defaults shared through {@code pulsario_settings}
 */
@Validated
@ConfigurationProperties(prefix = "catapulsar")
public record CatapulsarProperties(
        @Valid @NotNull Pulsar pulsar,
        @Valid @NotNull Cataponte cataponte,
        @Valid @NotNull Mongo mongo,
        @Valid @NotNull Ui ui,
        @Valid @NotNull Catametricas catametricas) {

    /** Smallest auto-refresh interval the UI may use, in seconds. */
    public static final int MIN_REFRESH_SECONDS = 2;

    /** Largest auto-refresh interval the UI may use, in seconds. */
    public static final int MAX_REFRESH_SECONDS = 300;

    /** Smallest collection interval accepted for Catametricas, in seconds. */
    public static final int MIN_COLLECT_SECONDS = 15;

    /** Largest collection interval accepted for Catametricas, in seconds. */
    public static final int MAX_COLLECT_SECONDS = 600;

    /**
     * Catametricas settings edited in Catapulsar (ADR 0007).
     *
     * @param collectIntervalSeconds default collection interval; keep equal to Catametricas' default
     */
    public record Catametricas(@Min(MIN_COLLECT_SECONDS) @Max(MAX_COLLECT_SECONDS) int collectIntervalSeconds) {
    }

    /**
     * Web UI defaults. Each can be overridden at runtime from the UI (stored in {@code pulsario_settings}).
     *
     * @param refreshIntervalSeconds how often the UI refreshes what is on screen
     */
    public record Ui(@Min(MIN_REFRESH_SECONDS) @Max(MAX_REFRESH_SECONDS) int refreshIntervalSeconds) {
    }

    /**
     * Pulsar settings (RES-2, PERF-5, PERF-6).
     *
     * @param serviceUrl admin HTTP URL, e.g. {@code http://host:8080}
     * @param brokerUrl binary protocol URL, e.g. {@code pulsar://host:6650}
     * @param listenerName advertised listener to use from outside the cluster; empty inside a pod
     * @param connectionTimeout client TCP connection timeout
     * @param operationTimeout client operation timeout (producer creation, send)
     * @param adminConnectTimeout admin HTTP connect timeout
     * @param adminReadTimeout admin HTTP read timeout
     * @param adminRequestTimeout admin HTTP total request timeout
     * @param producerIdleTimeout cached producers unused for this long are closed
     * @param maxCachedProducers upper bound on cached producers
     * @param maxPeekCount largest number of messages one peek may return
     */
    public record Pulsar(
            @NotBlank String serviceUrl,
            @NotBlank String brokerUrl,
            String listenerName,
            @NotNull Duration connectionTimeout,
            @NotNull Duration operationTimeout,
            @NotNull Duration adminConnectTimeout,
            @NotNull Duration adminReadTimeout,
            @NotNull Duration adminRequestTimeout,
            @NotNull Duration producerIdleTimeout,
            @Min(1) @Max(1_000) int maxCachedProducers,
            @Min(1) @Max(1_000) int maxPeekCount) {
    }

    /**
     * Cataponte client settings (ARCH-2, RES-2).
     *
     * @param url Cataponte base URL
     * @param connectTimeout HTTP connect timeout
     * @param readTimeout HTTP read timeout
     */
    public record Cataponte(
            @NotBlank String url,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {
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
