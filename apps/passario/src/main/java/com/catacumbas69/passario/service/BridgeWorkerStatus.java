package com.catacumbas69.passario.service;

import java.time.Instant;
import java.util.List;

/**
 * Point-in-time view of one bridge's runtime.
 *
 * @param bridgeId bridge id
 * @param name bridge name
 * @param state lifecycle state
 * @param running {@code true} when {@code state} is {@link BridgeState#RUNNING}
 * @param messagesForwarded messages delivered to every destination and acknowledged
 * @param errors failed deliveries and receive errors
 * @param inFlight messages sent but not yet confirmed
 * @param startedAt when the worker started relaying, or {@code null}
 * @param sourceTopic source topic
 * @param destinationTopics destination topics
 * @param ordered whether the bridge runs in ordered mode
 * @param lastError last error message, or {@code null}
 * @param nextRetryAt next start attempt after a failure, or {@code null}
 */
public record BridgeWorkerStatus(
        String bridgeId,
        String name,
        BridgeState state,
        boolean running,
        long messagesForwarded,
        long errors,
        int inFlight,
        Instant startedAt,
        String sourceTopic,
        List<String> destinationTopics,
        boolean ordered,
        String lastError,
        Instant nextRetryAt) {
}
