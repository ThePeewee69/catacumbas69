package com.catacumbas69.cataponte.service;

import com.catacumbas69.catacommons.consumers.DeadLetters;
import com.catacumbas69.catacommons.settings.SharedSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Follows the shared setting {@code dlq.max-deliveries} (ADR 0015): the DLQ policy is fixed when a bridge subscribes,
 * so when the value changes the bridges that have a DLQ are restarted.
 */
@Slf4j
@Component
public class DeadLetterSettingWatcher implements ApplicationListener<ApplicationReadyEvent> {

    private final SharedSettings settings;
    private final BridgeManager manager;
    private final BridgeWorkerFactory factory;
    private volatile int current;

    /**
     * Creates the watcher.
     *
     * @param settings the shared settings, if available
     * @param manager restarts the bridges
     * @param factory knows the value in force
     */
    public DeadLetterSettingWatcher(ObjectProvider<SharedSettings> settings, BridgeManager manager,
                                    BridgeWorkerFactory factory) {
        this.settings = settings.getIfAvailable();
        this.manager = manager;
        this.factory = factory;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        current = factory.maxDeliveries();
        if (settings != null) {
            settings.listen(DeadLetters.MAX_DELIVERIES_KEY, value -> changed());
        }
    }

    private void changed() {
        int next = factory.maxDeliveries();
        if (next == current) {
            return;
        }
        current = next;
        int restarted = manager.restartDeadLetterBridges();
        log.info("Deliveries before the DLQ are now {}: {} bridge(s) with a DLQ restarted", next, restarted);
    }
}
