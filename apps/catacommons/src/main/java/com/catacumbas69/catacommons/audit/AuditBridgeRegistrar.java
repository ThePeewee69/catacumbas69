package com.catacumbas69.catacommons.audit;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Registers this app's audit bridge {@code audit-<app>} in Cataponte once the app has started (ADR 0009), so
 * its events reach Cataudit. Runs in the background and is retried with back-off until Cataponte accepts it;
 * it never blocks start-up.
 */
public class AuditBridgeRegistrar implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(AuditBridgeRegistrar.class);

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
    public AuditBridgeRegistrar(SystemBridgeRegistry registry, String app, ScheduledExecutorService scheduler,
                                Duration firstRetry, Duration maxRetry) {
        this.registry = registry;
        this.bridge = SystemBridge.audit(app);
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

    /**
     * Returns the bridge this registrar registers.
     *
     * @return the audit bridge
     */
    public SystemBridge bridge() {
        return bridge;
    }

    private void schedule(Duration delay, Duration nextDelay) {
        try {
            scheduler.schedule(() -> attempt(nextDelay), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Audit bridge registration not scheduled, shutting down");
        }
    }

    private void attempt(Duration nextDelay) {
        try {
            registry.register(bridge);
            registered = true;
            LOG.info("Audit bridge {} registered: {} → {}", bridge.name(), bridge.sourceTopic(),
                    bridge.destinationTopic());
        } catch (RuntimeException e) {
            LOG.warn("Audit bridge {} not registered, retrying in {} s: {}", bridge.name(), nextDelay.toSeconds(),
                    e.getMessage());
            Duration doubled = nextDelay.multipliedBy(2);
            schedule(nextDelay, doubled.compareTo(maxRetry) > 0 ? maxRetry : doubled);
        }
    }
}
