package com.catacumbas69.catametricas.service;

/**
 * Turns Pulsar's cumulative counters into "how much since the last collection".
 *
 * <p>Pulsar resets a topic's counters when the topic is loaded again (broker restart, bundle unload or
 * topic reload). A counter that went down is therefore a reset, and the whole current value happened
 * since the reset. Traffic between the previous collection and the reset is lost; it is at most one
 * interval.
 */
public final class CounterDelta {

    private CounterDelta() {
    }

    /**
     * Computes the increase of a counter.
     *
     * @param previous value at the previous collection, or {@code null} if the topic was not seen before
     * @param current value now
     * @param baseline {@code true} on the very first collection: unknown topics only set the starting
     *        point, otherwise everything since the broker loaded them would land in one minute
     * @return the increase, never negative
     */
    public static long between(Long previous, long current, boolean baseline) {
        long now = Math.max(0, current);
        if (previous == null) {
            return baseline ? 0 : now;
        }
        return now >= previous ? now - previous : now;
    }
}
