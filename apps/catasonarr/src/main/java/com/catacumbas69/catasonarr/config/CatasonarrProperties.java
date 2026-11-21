package com.catacumbas69.catasonarr.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration under {@code catasonarr.*}.
 *
 * @param pulsar Pulsar admin and binary clients (RES-2)
 * @param mongo MongoDB timeouts (RES-2)
 * @param compat how Catasonarr presents itself to Sonarr's clients
 */
@Validated
@ConfigurationProperties(prefix = "catasonarr")
public record CatasonarrProperties(
        @Valid @NotNull Pulsar pulsar,
        @Valid @NotNull Mongo mongo,
        @Valid @NotNull Compat compat) {

    /**
     * Pulsar clients.
     *
     * @param serviceUrl admin API, e.g. {@code http://pulsar.pulsar.svc.cluster.local:8080}
     * @param connectTimeout admin connect timeout
     * @param readTimeout admin read timeout
     * @param requestTimeout admin request timeout
     * @param brokerUrl binary protocol, e.g. {@code pulsar://pulsar.pulsar.svc.cluster.local:6650}
     * @param listenerName advertised listener; empty in the cluster, {@code external} from the Mac
     * @param connectionTimeout binary connect timeout
     * @param operationTimeout binary operation timeout
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
     * MongoDB timeouts.
     *
     * @param serverSelectionTimeout how long to wait for a server
     * @param connectTimeout connect timeout
     * @param readTimeout read timeout
     */
    public record Mongo(
            @NotNull Duration serverSelectionTimeout,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {
    }

    /**
     * What {@code GET /api/v3/system/status} says (orch.md decision 109). Clients test the connection with it, and
     * some check the version, so it is Sonarr's version, not Catasonarr's own.
     *
     * @param instanceName the name shown by the clients, {@code Catasonarr}
     * @param version the Sonarr version reported, e.g. {@code 4.0.20.0}
     */
    public record Compat(
            @NotBlank String instanceName,
            @NotBlank String version) {
    }
}
