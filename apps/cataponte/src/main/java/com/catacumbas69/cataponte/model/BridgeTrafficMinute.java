package com.catacumbas69.cataponte.model;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Messages that crossed one topic of one bridge during one minute ({@code bridge_traffic_minutes}).
 * Only minutes with traffic are stored. Kept forever (no TTL). Unique on
 * {@code (bridgeId, role, topic, minute)}.
 */
@Getter
@Setter
@Document(collection = "bridge_traffic_minutes")
public class BridgeTrafficMinute {

    @Id
    private String id;

    private String bridgeId;

    /** Bridge name when the minute was written. */
    private String bridgeName;

    private TrafficRole role;

    /** Fully-qualified topic. */
    private String topic;

    /** Start of the minute (UTC). */
    private Instant minute;

    private long messages;

    /** Sum of payload sizes. */
    private long bytes;
}
