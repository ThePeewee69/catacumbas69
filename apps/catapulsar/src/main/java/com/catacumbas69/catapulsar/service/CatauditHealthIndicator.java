package com.catacumbas69.catapulsar.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reports whether Cataudit answers its liveness probe, as health component {@code cataudit}. Not part of
 * readiness: only the Audit panel depends on it (RES-12).
 */
@Component("cataudit")
public class CatauditHealthIndicator implements HealthIndicator {

    private final RestClient cataudit;
    private final CatauditEndpoint endpoint;

    /**
     * Creates the indicator.
     *
     * @param cataudit HTTP client for Cataudit
     * @param endpoint Cataudit base URL
     */
    public CatauditHealthIndicator(@Qualifier("catauditRestClient") RestClient cataudit, CatauditEndpoint endpoint) {
        this.cataudit = cataudit;
        this.endpoint = endpoint;
    }

    @Override
    public Health health() {
        try {
            cataudit.get().uri(endpoint.url() + "/actuator/health/liveness").retrieve().toBodilessEntity();
            return Health.up().withDetail("url", endpoint.url()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("url", endpoint.url()).withDetail("error", e.getMessage()).build();
        }
    }
}
