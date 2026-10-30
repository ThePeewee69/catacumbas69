package com.catacumbas69.pulsario.service;

import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the Pulsar admin API answers, as health component {@code pulsar}. Not part of
 * readiness: the UI degrades per feature instead (RES-12).
 */
@Component("pulsar")
public class PulsarHealthIndicator implements HealthIndicator {

    private static final long TIMEOUT_SECONDS = 3;

    private final PulsarAdmin admin;

    /**
     * Creates the indicator.
     *
     * @param admin Pulsar admin client
     */
    public PulsarHealthIndicator(PulsarAdmin admin) {
        this.admin = admin;
    }

    @Override
    public Health health() {
        try {
            int tenants = admin.tenants().getTenantsAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size();
            return Health.up().withDetail("tenants", tenants).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
