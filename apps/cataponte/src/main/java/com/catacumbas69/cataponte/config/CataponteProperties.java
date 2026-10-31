package com.catacumbas69.cataponte.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration under the {@code cataponte.*} prefix (CFG-1). Every value has a default in
 * {@code application.properties}; environment variables override them (ARCH-8).
 *
 * @param pulsar Pulsar client connection settings
 * @param bridge bridge runtime tuning
 * @param mongo MongoDB client timeouts
 * @param cors CORS settings for browser clients
 */
@Validated
@ConfigurationProperties(prefix = "cataponte")
public record CataponteProperties(
        @Valid @NotNull Pulsar pulsar,
        @Valid @NotNull Bridge bridge,
        @Valid @NotNull Mongo mongo,
        @Valid @NotNull Cors cors) {

    /**
     * Pulsar client settings (RES-2, RES-3).
     *
     * @param brokerUrl binary protocol URL, e.g. {@code pulsar://host:6650}
     * @param listenerName advertised listener to use from outside the cluster; empty inside a pod
     * @param connectionTimeout TCP connection timeout
     * @param operationTimeout timeout for producer/consumer creation and other client operations
     * @param startingBackoff first reconnect delay
     * @param maxBackoff upper bound for reconnect delays
     * @param serviceUrl admin HTTP URL, e.g. {@code http://host:8080}; used at start-up to create the
     *                   namespace and topics (ADR 0008)
     * @param adminConnectTimeout admin HTTP connect timeout
     * @param adminReadTimeout admin HTTP read timeout
     * @param adminRequestTimeout admin HTTP total request timeout
     */
    public record Pulsar(
            @NotBlank String brokerUrl,
            String listenerName,
            @NotNull Duration connectionTimeout,
            @NotNull Duration operationTimeout,
            @NotNull Duration startingBackoff,
            @NotNull Duration maxBackoff,
            @NotBlank String serviceUrl,
            @NotNull Duration adminConnectTimeout,
            @NotNull Duration adminReadTimeout,
            @NotNull Duration adminRequestTimeout) {
    }

    /**
     * Bridge runtime tuning (PERF-2..4, RES-5, RES-7, RES-8).
     *
     * @param reconcileInterval how often the desired state in MongoDB is reconciled
     * @param defaultMaxInFlight unacknowledged messages allowed per bridge when the bridge sets none
     * @param sendTimeout producer send timeout
     * @param batchingMaxPublishDelay producer batching window
     * @param batchingMaxMessages maximum messages per producer batch
     * @param receiverQueueSize consumer prefetch size
     * @param acknowledgmentGroupTime how long acknowledgements are grouped before being sent
     * @param negativeAckRedeliveryDelay delay before a negatively acknowledged message is redelivered
     * @param startBackoffInitial first retry delay after a bridge fails to start
     * @param startBackoffMax upper bound for start retry delays
     * @param shutdownTimeout time a stopping bridge waits for in-flight messages
     */
    public record Bridge(
            @NotNull Duration reconcileInterval,
            @Min(1) @Max(100_000) int defaultMaxInFlight,
            @NotNull Duration sendTimeout,
            @NotNull Duration batchingMaxPublishDelay,
            @Min(1) int batchingMaxMessages,
            @Min(1) int receiverQueueSize,
            @NotNull Duration acknowledgmentGroupTime,
            @NotNull Duration negativeAckRedeliveryDelay,
            @NotNull Duration startBackoffInitial,
            @NotNull Duration startBackoffMax,
            @NotNull Duration shutdownTimeout) {
    }

    /**
     * MongoDB client timeouts (RES-2). The connection string itself is {@code spring.data.mongodb.uri}.
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
     * CORS settings (API-7). An empty list disables CORS.
     *
     * @param allowedOrigins origins allowed to call {@code /api/**} from a browser
     */
    public record Cors(List<String> allowedOrigins) {

        /**
         * Normalises a missing list to an empty one and drops blank entries.
         *
         * @param allowedOrigins configured origins, may be {@code null}
         */
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of()
                    : allowedOrigins.stream().filter(origin -> !origin.isBlank()).toList();
        }
    }
}
