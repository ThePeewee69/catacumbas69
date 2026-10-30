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

    /**
     * Creates the indicator.
     *
     * @param passario HTTP client for Passário
     */
    public PassarioHealthIndicator(@Qualifier("passarioRestClient") RestClient passario) {
        this.passario = passario;
    }

    @Override
    public Health health() {
        try {
            passario.get().uri("/actuator/health/liveness").retrieve().toBodilessEntity();
            return Health.up().build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
