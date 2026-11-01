package com.catacumbas69.cataponte.dto;

import java.time.Instant;
import java.util.List;

/**
 * Status of one bridge over every replica that reported it (ADR 0012).
 *
 * @param bridgeId bridge id
 * @param name bridge name
 * @param state the most advanced state of any replica: {@code RUNNING} if one relays, then {@code STARTING},
 *              {@code STOPPING}, {@code FAILED}, {@code BLOCKED}, {@code STOPPED}
 * @param running {@code true} when at least one replica relays
 * @param messagesForwarded messages relayed, summed over the replicas (since each worker started)
 * @param errors failed deliveries, summed over the replicas
 * @param inFlight messages sent but not yet confirmed, summed over the replicas
 * @param startedAt earliest start of a relaying replica, or {@code null}
 * @param sourceTopic source topic
 * @param destinationTopics destination topics
 * @param ordered whether the bridge runs in ordered mode
 * @param lastError last error of a replica that is not relaying, else of any replica; {@code null} if none
 * @param nextRetryAt earliest next start attempt of a failed replica, or {@code null}
 * @param replicas replicas that run (or try to run) the bridge
 * @param activeReplicas replicas whose consumer receives messages: one for a {@code Failover} subscription (the
 *                       others stand by), all running ones for {@code Shared} and {@code Key_Shared}
 */
public record BridgeRuntimeStatus(
        String bridgeId,
        String name,
        String state,
        boolean running,
        long messagesForwarded,
        long errors,
        int inFlight,
        Instant startedAt,
        String sourceTopic,
        List<String> destinationTopics,
        boolean ordered,
        String lastError,
        Instant nextRetryAt,
        int replicas,
        int activeReplicas) {
}
