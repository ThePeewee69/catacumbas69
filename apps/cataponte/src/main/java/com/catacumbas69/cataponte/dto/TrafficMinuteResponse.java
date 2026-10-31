package com.catacumbas69.cataponte.dto;

import com.catacumbas69.cataponte.model.BridgeTrafficMinute;
import com.catacumbas69.cataponte.model.TrafficRole;
import java.time.Instant;

/**
 * Messages that crossed one topic of one bridge during one minute.
 *
 * @param minute start of the minute (UTC)
 * @param role {@code SOURCE} or {@code DESTINATION}
 * @param topic fully-qualified topic
 * @param messages message count
 * @param bytes payload bytes
 */
public record TrafficMinuteResponse(Instant minute, TrafficRole role, String topic, long messages, long bytes) {

    /**
     * Converts a stored minute.
     *
     * @param minute stored minute
     * @return the response record
     */
    public static TrafficMinuteResponse from(BridgeTrafficMinute minute) {
        return new TrafficMinuteResponse(minute.getMinute(), minute.getRole(), minute.getTopic(), minute.getMessages(),
                minute.getBytes());
    }
}
