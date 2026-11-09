package com.catacumbas69.catapulsar.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reports whether Catavigia answers its liveness probe, as health component {@code catavigia}. Not part of
 * readiness: only the Alerts panel and badge depend on it (RES-12).
 */
@Component("catavigia")
public class CatavigiaHealthIndicator implements HealthIndicator {

    private final RestClient catavigia;
    private final CatavigiaEndpoint endpoint;

    /**
     * Creates the indicator.
     *
     * @param catavigia HTTP client for Catavigia
     * @param endpoint Catavigia base URL
     */
    public CatavigiaHealthIndicator(@Qualifier("catavigiaRestClient") RestClient catavigia,
                                    CatavigiaEndpoint endpoint) {
        this.catavigia = catavigia;
        this.endpoint = endpoint;
    }

    @Override
    public Health health() {
        try {
            catavigia.get().uri(endpoint.url() + "/actuator/health/liveness").retrieve().toBodilessEntity();
            return Health.up().withDetail("url", endpoint.url()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("url", endpoint.url()).withDetail("error", e.getMessage()).build();
        }
    }
}
