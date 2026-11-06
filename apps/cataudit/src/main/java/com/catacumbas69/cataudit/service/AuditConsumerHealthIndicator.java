package com.catacumbas69.cataudit.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code auditConsumer} component of {@code /actuator/health}: up while the audit topic is consumed. Not part
 * of readiness, so the query API stays available while Pulsar is down (OBS-2).
 */
@Component
public class AuditConsumerHealthIndicator implements HealthIndicator {

    private final AuditConsumer consumer;

    /**
     * Creates the indicator.
     *
     * @param consumer the audit consumer
     */
    public AuditConsumerHealthIndicator(AuditConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public Health health() {
        if (consumer.isConnected()) {
            Health.Builder up = Health.up().withDetail("state", consumer.gate().state());
            if (consumer.gate().reason() != null) {
                up.withDetail("reason", consumer.gate().reason());
            }
            return up.build();
        }
        Health.Builder down = Health.down().withDetail("state", consumer.gate().state());
        if (consumer.lastError() != null) {
            down.withDetail("error", consumer.lastError());
        }
        return down.build();
    }
}
