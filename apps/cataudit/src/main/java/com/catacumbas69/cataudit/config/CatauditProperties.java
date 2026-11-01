package com.catacumbas69.cataudit.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration under the {@code cataudit.*} prefix (CFG-1).
 *
 * @param pulsar Pulsar admin and client settings
 * @param consumer how the audit topic is consumed
 * @param mongo MongoDB client timeouts
 * @param api query API limits
 */
@Validated
@ConfigurationProperties(prefix = "cataudit")
public record CatauditProperties(
        @Valid @NotNull Pulsar pulsar,
        @Valid @NotNull Consumer consumer,
        @Valid @NotNull Mongo mongo,
        @Valid @NotNull Api api) {

    /**
     * Pulsar settings (RES-2).
     *
     * @param serviceUrl admin HTTP URL, e.g. {@code http://host:8080}
     * @param brokerUrl binary protocol URL, e.g. {@code pulsar://host:6650}
     * @param listenerName advertised listener to use from outside the cluster; empty inside a pod
     * @param connectionTimeout binary client TCP connection timeout
     * @param operationTimeout binary client operation timeout
     * @param adminConnectTimeout admin HTTP connect timeout
     * @param adminReadTimeout admin HTTP read timeout
     * @param adminRequestTimeout admin HTTP total request timeout
     */
    public record Pulsar(
            @NotBlank String serviceUrl,
            @NotBlank String brokerUrl,
            String listenerName,
            @NotNull Duration connectionTimeout,
            @NotNull Duration operationTimeout,
            @NotNull Duration adminConnectTimeout,
            @NotNull Duration adminReadTimeout,
            @NotNull Duration adminRequestTimeout) {
    }

    /**
     * Consumer of {@code catacumbas69/cataudit/audit} (ADR 0012: {@code Shared}, so every replica takes a share).
     *
     * @param subscription subscription name, the same in every replica
     * @param receiverQueueSize messages prefetched per replica
     * @param negativeAckRedeliveryDelay wait before a message that could not be stored is delivered again
     * @param retryInterval wait before subscribing again after a failure
     */
    public record Consumer(
            @NotBlank String subscription,
            @Min(1) @Max(10_000) int receiverQueueSize,
            @NotNull Duration negativeAckRedeliveryDelay,
            @NotNull Duration retryInterval) {
    }

    /**
     * MongoDB client timeouts (RES-2).
     *
     * @param serverSelectionTimeout wait for a usable server
     * @param connectTimeout TCP connect timeout
     * @param readTimeout socket read timeout
     */
    public record Mongo(
            @NotNull Duration serverSelectionTimeout,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {
    }

    /**
     * Query API limits.
     *
     * @param defaultPageSize entries per page when the client sends none
     * @param maxPageSize largest page a client may ask for
     */
    public record Api(
            @Min(1) @Max(1_000) int defaultPageSize,
            @Min(1) @Max(1_000) int maxPageSize) {
    }
}
