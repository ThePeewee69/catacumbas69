package com.catacumbas69.passario.service;

import com.catacumbas69.passario.model.BridgeDocument;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Remembers why a bridge failed to start and when to try again (RES-5).
 *
 * @param attempts consecutive failed attempts
 * @param nextAttemptAt earliest time of the next attempt
 * @param lastError last failure message
 * @param bridgeName bridge name, for status reporting
 * @param sourceTopic source topic, for status reporting
 * @param destinationTopics destination topics, for status reporting
 * @param ordered ordering mode, for status reporting
 */
public record StartFailure(
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        String bridgeName,
        String sourceTopic,
        List<String> destinationTopics,
        boolean ordered) {

    /**
     * Computes the next failure record with exponential back-off: {@code initial × 2^(attempts-1)},
     * capped at {@code max}.
     *
     * @param previous previous failure for this bridge, or {@code null}
     * @param bridge bridge definition
     * @param error failure message
     * @param now current time
     * @param initial first delay
     * @param max maximum delay
     * @return the new failure record
     */
    public static StartFailure next(StartFailure previous, BridgeDocument bridge, String error, Instant now,
                                    Duration initial, Duration max) {
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        int exponent = Math.min(attempts - 1, 30);
        long delayMillis = Math.min(max.toMillis(), initial.toMillis() * (1L << exponent));
        return new StartFailure(attempts, now.plusMillis(delayMillis), error, bridge.getName(),
                bridge.getSource() == null ? null : bridge.getSource().getTopic(),
                bridge.resolveDestinationTopics(), bridge.isOrderedEffective());
    }

    /**
     * Whether a new attempt is allowed at {@code now}.
     *
     * @param now current time
     * @return {@code true} once the back-off delay has passed
     */
    public boolean isDue(Instant now) {
        return !now.isBefore(nextAttemptAt);
    }
}
