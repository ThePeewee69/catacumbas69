package com.catacumbas69.passario.service;

import com.catacumbas69.passario.config.PassarioProperties;
import com.catacumbas69.passario.model.BridgeDocument;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.pulsar.client.api.PulsarClient;
import org.springframework.stereotype.Component;

/**
 * Creates {@link BridgeWorker} instances. Exists so {@link BridgeManager} can be unit-tested without
 * Pulsar.
 */
@Component
public class BridgeWorkerFactory {

    private final PulsarClient client;
    private final PassarioProperties properties;
    private final MeterRegistry meterRegistry;
    private final TrafficRecorder traffic;

    /**
     * Creates the factory.
     *
     * @param client shared Pulsar client
     * @param properties application properties
     * @param meterRegistry registry for per-bridge meters
     * @param traffic per-topic message counts stored in MongoDB
     */
    public BridgeWorkerFactory(PulsarClient client, PassarioProperties properties, MeterRegistry meterRegistry,
                               TrafficRecorder traffic) {
        this.client = client;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.traffic = traffic;
    }

    /**
     * Creates an unstarted worker for a bridge definition.
     *
     * @param bridge bridge definition
     * @return a new worker
     */
    public BridgeWorker create(BridgeDocument bridge) {
        return new BridgeWorker(bridge, client, properties.bridge(),
                new BridgeMetrics(meterRegistry, bridge.getName()), traffic);
    }
}
