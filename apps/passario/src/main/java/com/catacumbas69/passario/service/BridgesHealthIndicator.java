package com.catacumbas69.passario.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports bridge runtime counts under {@code /actuator/health} as component {@code bridges}.
 *
 * <p>Always {@code UP}: a failing bridge must not take the API out of the readiness group, so this
 * component is informational and is not part of readiness (OBS-2).
 */
@Component("bridges")
public class BridgesHealthIndicator implements HealthIndicator {

    private final BridgeManager manager;

    /**
     * Creates the indicator.
     *
     * @param manager bridge runtime
     */
    public BridgesHealthIndicator(BridgeManager manager) {
        this.manager = manager;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("running", manager.getRunningCount())
                .withDetail("failed", manager.getFailedCount())
                .build();
    }
}
