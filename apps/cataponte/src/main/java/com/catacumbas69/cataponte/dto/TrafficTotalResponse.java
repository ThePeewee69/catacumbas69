package com.catacumbas69.cataponte.dto;

import com.catacumbas69.cataponte.model.BridgeTrafficTotal;
import com.catacumbas69.cataponte.model.TrafficRole;
import java.time.Instant;

/**
 * All-time count of messages that crossed one topic of one bridge.
 *
 * @param bridgeId bridge id
 * @param bridgeName latest bridge name
 * @param role {@code SOURCE} (consumed and delivered everywhere) or {@code DESTINATION} (accepted there)
 * @param topic fully-qualified topic
 * @param messages message count
 * @param bytes payload bytes
 * @param firstAt first minute with traffic
 * @param lastAt last minute with traffic
 */
public record TrafficTotalResponse(
        String bridgeId,
        String bridgeName,
        TrafficRole role,
        String topic,
        long messages,
        long bytes,
        Instant firstAt,
        Instant lastAt) {

    /**
     * Converts a stored total.
     *
     * @param total stored total
     * @return the response record
     */
    public static TrafficTotalResponse from(BridgeTrafficTotal total) {
        return new TrafficTotalResponse(total.getBridgeId(), total.getBridgeName(), total.getRole(), total.getTopic(),
                total.getMessages(), total.getBytes(), total.getFirstAt(), total.getLastAt());
    }
}
