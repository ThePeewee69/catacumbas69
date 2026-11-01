package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.model.TaskRun;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health component {@code collector}: whether this instance receives collection tasks and how its last one went.
 * Always UP (a failed collection or Pulsar being down does not make the pod unhealthy; the API keeps
 * answering from MongoDB); details show the state.
 */
@Component("collector")
public class CollectorHealthIndicator implements HealthIndicator {

    private final CollectorState state;
    private final CollectTaskConsumer consumer;

    /**
     * Creates the indicator.
     *
     * @param state this instance's collection state
     * @param consumer the task consumer
     */
    public CollectorHealthIndicator(CollectorState state, CollectTaskConsumer consumer) {
        this.state = state;
        this.consumer = consumer;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up()
                .withDetail("instance", state.instance())
                .withDetail("consuming", consumer.isConnected());
        TaskRun last = state.lastRun();
        if (last != null) {
            health.withDetail("lastTask", last.id())
                    .withDetail("lastTaskFinishedAt", last.finishedAt())
                    .withDetail("topics", last.topics())
                    .withDetail("failedTopics", last.failedTopics());
        }
        if (state.lastError() != null) {
            health.withDetail("lastError", state.lastError());
        } else if (consumer.lastError() != null) {
            health.withDetail("lastError", "subscribe: " + consumer.lastError());
        }
        return health.build();
    }
}
