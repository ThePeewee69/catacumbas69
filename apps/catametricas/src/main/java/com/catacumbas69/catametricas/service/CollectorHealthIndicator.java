package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.model.CollectorStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health component {@code collector}: whether this instance collects and how the last collection went.
 * Always UP (a follower or a failed collection does not make the pod unhealthy); details show the state.
 */
@Component("collector")
public class CollectorHealthIndicator implements HealthIndicator {

    private final StatsCollector collector;

    /**
     * Creates the indicator.
     *
     * @param collector the collector
     */
    public CollectorHealthIndicator(StatsCollector collector) {
        this.collector = collector;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up().withDetail("leader", collector.isLeader());
        CollectorStatus last = collector.lastStatus();
        if (last != null) {
            health.withDetail("lastCollection", last.finishedAt())
                    .withDetail("topics", last.topics())
                    .withDetail("failedTopics", last.failedTopics())
                    .withDetail("intervalSeconds", last.intervalSeconds());
        }
        if (collector.lastError() != null) {
            health.withDetail("lastError", collector.lastError());
        }
        return health.build();
    }
}
