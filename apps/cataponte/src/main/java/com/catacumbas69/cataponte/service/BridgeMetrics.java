package com.catacumbas69.cataponte.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Micrometer meters for one bridge (OBS-5), tagged with the bridge name. Exported to Prometheus as
 * {@code cataponte_messages_forwarded_total}, {@code cataponte_forward_errors_total} and
 * {@code cataponte_messages_in_flight}.
 */
public class BridgeMetrics {

    private final MeterRegistry registry;
    private final Tags tags;
    private final Counter forwarded;
    private final Counter errors;
    private final List<Meter> meters = new ArrayList<>();

    /**
     * Registers the counters for a bridge.
     *
     * @param registry meter registry
     * @param bridgeName bridge name used as the {@code bridge} tag
     */
    public BridgeMetrics(MeterRegistry registry, String bridgeName) {
        this.registry = registry;
        this.tags = Tags.of("bridge", bridgeName);
        this.forwarded = register(Counter.builder("cataponte.messages.forwarded")
                .description("Messages delivered to every destination and acknowledged")
                .tags(tags)
                .register(registry));
        this.errors = register(Counter.builder("cataponte.forward.errors")
                .description("Failed deliveries and receive errors")
                .tags(tags)
                .register(registry));
    }

    /**
     * Registers the in-flight gauge.
     *
     * @param inFlight supplier of the current number of unconfirmed messages
     */
    public void registerInFlight(Supplier<Number> inFlight) {
        register(Gauge.builder("cataponte.messages.in.flight", inFlight)
                .description("Messages sent but not yet confirmed by every destination")
                .tags(tags)
                .register(registry));
    }

    /** Counts one message delivered to every destination. */
    public void forwarded() {
        forwarded.increment();
    }

    /** Counts one failure. */
    public void error() {
        errors.increment();
    }

    /**
     * Returns the number of forwarded messages since the worker started.
     *
     * @return forwarded count
     */
    public long forwardedCount() {
        return (long) forwarded.count();
    }

    /**
     * Returns the number of failures since the worker started.
     *
     * @return error count
     */
    public long errorCount() {
        return (long) errors.count();
    }

    /** Unregisters every meter of this bridge, so a stopped bridge leaves no stale series. */
    public void remove() {
        meters.forEach(registry::remove);
        meters.clear();
    }

    private <T extends Meter> T register(T meter) {
        meters.add(meter);
        return meter;
    }
}
