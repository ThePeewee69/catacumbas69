package com.catacumbas69.cataponte.service;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.consumers.DeadLetters;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.cataponte.config.CataponteProperties;
import com.catacumbas69.cataponte.model.BridgeDocument;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.pulsar.client.api.PulsarClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Creates {@link BridgeWorker} instances. Exists so {@link BridgeManager} can be unit-tested without
 * Pulsar.
 */
@Component
public class BridgeWorkerFactory {

    private final PulsarClient client;
    private final CataponteProperties properties;
    private final MeterRegistry meterRegistry;
    private final TrafficRecorder traffic;
    private final CataponteInstance instance;
    private final SharedSettings sharedSettings;
    private final int defaultMaxDeliveries;

    /**
     * Creates the factory.
     *
     * @param client shared Pulsar client
     * @param properties application properties
     * @param meterRegistry registry for per-bridge meters
     * @param traffic per-topic message counts stored in MongoDB
     * @param instance this replica, named in the consumers it creates
     * @param sharedSettings source of {@code dlq.max-deliveries}, if available
     * @param commons catacommons configuration, for the default number of deliveries
     */
    public BridgeWorkerFactory(PulsarClient client, CataponteProperties properties, MeterRegistry meterRegistry,
                               TrafficRecorder traffic, CataponteInstance instance,
                               ObjectProvider<SharedSettings> sharedSettings, CatacommonsProperties commons) {
        this.client = client;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.traffic = traffic;
        this.instance = instance;
        this.sharedSettings = sharedSettings.getIfAvailable();
        this.defaultMaxDeliveries = commons.consumers().maxDeliveries();
    }

    /**
     * Creates an unstarted worker for a bridge definition.
     *
     * @param bridge bridge definition
     * @return a new worker
     */
    public BridgeWorker create(BridgeDocument bridge) {
        return new BridgeWorker(bridge, client, properties.bridge(),
                new BridgeMetrics(meterRegistry, bridge.getName()), traffic,
                "cataponte@" + instance.id() + "/" + bridge.getName(), maxDeliveries());
    }

    /**
     * Returns the deliveries before the DLQ in force now: {@code dlq.max-deliveries}, or the default.
     *
     * @return deliveries
     */
    public int maxDeliveries() {
        return DeadLetters.maxDeliveries(sharedSettings, defaultMaxDeliveries);
    }
}
