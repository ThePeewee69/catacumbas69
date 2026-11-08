package com.catacumbas69.catavigia.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration of Catavigia ({@code catavigia.*}). The alert thresholds are not here: they are shared
 * settings edited in Catapulsar (catacommons {@code AlertSetting}).
 *
 * @param pulsar Pulsar admin API and binary client
 * @param mongo MongoDB client timeouts
 * @param tasks check tasks on Pulsar
 * @param checks what the checks need
 * @param apps where the watched apps answer
 * @param api query API limits
 */
@Validated
@ConfigurationProperties(prefix = "catavigia")
public record CatavigiaProperties(
        @Valid @NotNull Pulsar pulsar,
        @Valid @NotNull Mongo mongo,
        @Valid @NotNull Tasks tasks,
        @Valid @NotNull Checks checks,
        @Valid @NotNull Apps apps,
        @Valid @NotNull Api api) {

    /**
     * Pulsar settings (RES-2): the admin API counts brokers, runs the health check and provisions the namespace;
     * the binary client carries tasks, events, settings and the consumers' status.
     *
     * @param serviceUrl admin HTTP URL, e.g. {@code http://host:8080}
     * @param connectTimeout HTTP connect timeout
     * @param readTimeout HTTP read timeout
     * @param requestTimeout HTTP total request timeout
     * @param brokerUrl binary protocol URL, e.g. {@code pulsar://host:6650}
     * @param listenerName advertised listener to use from outside the cluster; empty inside a pod
     * @param connectionTimeout binary client TCP connection timeout
     * @param operationTimeout binary client operation timeout
     */
    public record Pulsar(
            @NotBlank String serviceUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @NotNull Duration requestTimeout,
            @NotBlank String brokerUrl,
            String listenerName,
            @NotNull Duration connectionTimeout,
            @NotNull Duration operationTimeout) {
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

    /**
     * Check tasks on {@code catacumbas69/catavigia/check-tasks} (orch.md decision 75).
     *
     * @param subscription name of the {@code Shared} subscription every instance joins
     * @param receiverQueueSize tasks an instance fetches ahead; small, so checks spread over the instances
     * @param subscribeRetryInterval wait before subscribing again after a failure
     * @param retryDelay first wait before a failed task is delivered again
     * @param maxRetryDelay longest wait before a failed task is delivered again (the wait doubles up to this)
     * @param runRetention how long task claims are kept (TTL index)
     */
    public record Tasks(
            @NotBlank String subscription,
            @Min(1) @Max(1_000) int receiverQueueSize,
            @NotNull Duration subscribeRetryInterval,
            @NotNull Duration retryDelay,
            @NotNull Duration maxRetryDelay,
            @NotNull Duration runRetention) {
    }

    /**
     * What the checks need besides the shared thresholds.
     *
     * @param initialDelay wait before the first round after start-up
     * @param httpTimeout timeout of every HTTP call to an app
     * @param expectedBrokers {@code pulsar.broker.down} opens when fewer brokers are active
     * @param consumerStaleAfter a consumer replica that has not reported for this long is gone
     */
    public record Checks(
            @NotNull Duration initialDelay,
            @NotNull Duration httpTimeout,
            @Min(1) @Max(100) int expectedBrokers,
            @NotNull Duration consumerStaleAfter) {
    }

    /**
     * Base URLs of the watched apps. For Cataponte, Cataudit and Catametricas the URL set in Catapulsar's Settings
     * (settings topic) wins.
     *
     * @param catapulsarUrl Catapulsar
     * @param cataponteUrl Cataponte, until the settings topic has one
     * @param catametricasUrl Catametricas, until the settings topic has one
     * @param catauditUrl Cataudit, until the settings topic has one
     */
    public record Apps(
            @NotBlank String catapulsarUrl,
            @NotBlank String cataponteUrl,
            @NotBlank String catametricasUrl,
            @NotBlank String catauditUrl) {
    }

    /**
     * Query API limits.
     *
     * @param defaultPageSize archive page size when none is asked
     * @param maxPageSize largest archive page
     */
    public record Api(
            @Min(1) @Max(1_000) int defaultPageSize,
            @Min(1) @Max(1_000) int maxPageSize) {
    }
}
