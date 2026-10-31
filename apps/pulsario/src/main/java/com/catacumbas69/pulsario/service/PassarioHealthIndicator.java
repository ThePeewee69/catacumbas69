package com.catacumbas69.pulsario.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reports whether Passário answers its liveness probe, as health component {@code passario}. Not part
 * of readiness: only the Bridges panel depends on it (RES-12).
 */
@Component("passario")
public class PassarioHealthIndicator implements HealthIndicator {

    private final RestClient passario;
    private final PassarioEndpoint endpoint;

    /**
     * Creates the indicator.
     *
     * @param passario HTTP client for Passário
     * @param endpoint Passário base URL
     */
    public PassarioHealthIndicator(@Qualifier("passarioRestClient") RestClient passario,
                                   PassarioEndpoint endpoint) {
        this.passario = passario;
        this.endpoint = endpoint;
    }

    @Override
    public Health health() {
        try {
            passario.get().uri(endpoint.url() + "/actuator/health/liveness").retrieve().toBodilessEntity();
            return Health.up().withDetail("url", endpoint.url()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("url", endpoint.url()).withDetail("error", e.getMessage()).build();
        }
    }
}
