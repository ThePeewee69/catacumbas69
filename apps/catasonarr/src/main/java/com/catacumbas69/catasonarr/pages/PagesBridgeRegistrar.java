package com.catacumbas69.catasonarr.pages;

import com.catacumbas69.catacommons.audit.SystemBridge;
import com.catacumbas69.catacommons.audit.SystemBridgeRegistry;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Registers the system bridge {@code pages-catasonarr} in Cataponte once Catasonarr has started (orch.md decision
 * 118), as catacommons registers {@code audit-catasonarr}: in the background, retried with back-off until Cataponte
 * accepts it, never blocking start-up. Registering again is idempotent.
 */
public class PagesBridgeRegistrar implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(PagesBridgeRegistrar.class);

    private final SystemBridgeRegistry registry;
    private final SystemBridge bridge;
    private final ScheduledExecutorService scheduler;
    private final Duration firstRetry;
    private final Duration maxRetry;
    private volatile boolean registered;

    /**
     * Creates the registrar.
     *
     * @param registry registers the bridge in Cataponte
     * @param app this app's name
     * @param scheduler runs the attempts
     * @param firstRetry wait after the first failure; doubled after each further failure
     * @param maxRetry upper bound for the wait
     */
    public PagesBridgeRegistrar(SystemBridgeRegistry registry, String app, ScheduledExecutorService scheduler,
                                Duration firstRetry, Duration maxRetry) {
        this.registry = registry;
        this.bridge = PagesTopics.bridge(app);
        this.scheduler = scheduler;
        this.firstRetry = firstRetry;
        this.maxRetry = maxRetry;
    }

    /**
     * Starts registering in the background.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        schedule(Duration.ZERO, firstRetry);
    }

    /**
     * Tells whether the bridge was registered by this process.
     *
     * @return {@code true} once Cataponte accepted it
     */
    public boolean isRegistered() {
        return registered;
    }

    private void schedule(Duration delay, Duration nextDelay) {
        try {
            scheduler.schedule(() -> attempt(nextDelay), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Pages bridge registration not scheduled, shutting down");
        }
    }

    private void attempt(Duration nextDelay) {
        try {
            registry.register(bridge);
            registered = true;
            LOG.info("Pages bridge {} registered: {} → {}", bridge.name(), bridge.sourceTopic(),
                    bridge.destinationTopic());
        } catch (RuntimeException e) {
            LOG.warn("Pages bridge {} not registered, retrying in {} s: {}", bridge.name(), nextDelay.toSeconds(),
                    e.getMessage());
            Duration doubled = nextDelay.multipliedBy(2);
            schedule(nextDelay, doubled.compareTo(maxRetry) > 0 ? maxRetry : doubled);
        }
    }
}
