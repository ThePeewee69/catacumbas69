package com.catacumbas69.catapulsar.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reports whether Cataponte answers its liveness probe, as health component {@code cataponte}. Not part
 * of readiness: only the Bridges panel depends on it (RES-12).
 */
@Component("cataponte")
public class CataponteHealthIndicator implements HealthIndicator {

    private final RestClient cataponte;
    private final CataponteEndpoint endpoint;

    /**
     * Creates the indicator.
     *
     * @param cataponte HTTP client for Cataponte
     * @param endpoint Cataponte base URL
     */
    public CataponteHealthIndicator(@Qualifier("cataponteRestClient") RestClient cataponte,
                                   CataponteEndpoint endpoint) {
        this.cataponte = cataponte;
        this.endpoint = endpoint;
    }

    @Override
    public Health health() {
        try {
            cataponte.get().uri(endpoint.url() + "/actuator/health/liveness").retrieve().toBodilessEntity();
            return Health.up().withDetail("url", endpoint.url()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("url", endpoint.url()).withDetail("error", e.getMessage()).build();
        }
    }
}
