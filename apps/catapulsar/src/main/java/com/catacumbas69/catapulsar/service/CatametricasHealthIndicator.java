package com.catacumbas69.catapulsar.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reports whether Catametricas answers its liveness probe, as health component {@code catametricas}. Not part of
 * readiness: only the overviews depend on it (RES-12).
 */
@Component("catametricas")
public class CatametricasHealthIndicator implements HealthIndicator {

    private final RestClient catametricas;
    private final CatametricasEndpoint endpoint;

    /**
     * Creates the indicator.
     *
     * @param catametricas HTTP client for Catametricas
     * @param endpoint Catametricas base URL
     */
    public CatametricasHealthIndicator(@Qualifier("catametricasRestClient") RestClient catametricas,
                                       CatametricasEndpoint endpoint) {
        this.catametricas = catametricas;
        this.endpoint = endpoint;
    }

    @Override
    public Health health() {
        try {
            catametricas.get().uri(endpoint.url() + "/actuator/health/liveness").retrieve().toBodilessEntity();
            return Health.up().withDetail("url", endpoint.url()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("url", endpoint.url()).withDetail("error", e.getMessage()).build();
        }
    }
}
