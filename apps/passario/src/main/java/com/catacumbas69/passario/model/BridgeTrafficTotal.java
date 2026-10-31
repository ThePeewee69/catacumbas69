package com.catacumbas69.passario.model;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * All-time message count for one topic of one bridge ({@code bridge_traffic_totals}). Unique on
 * {@code (bridgeId, role, topic)}. Kept when the bridge is deleted.
 */
@Getter
@Setter
@Document(collection = "bridge_traffic_totals")
public class BridgeTrafficTotal {

    @Id
    private String id;

    private String bridgeId;

    /** Latest known bridge name. */
    private String bridgeName;

    private TrafficRole role;

    /** Fully-qualified topic. */
    private String topic;

    private long messages;

    /** Sum of payload sizes. */
    private long bytes;

    /** Start of the first minute with traffic. */
    private Instant firstAt;

    /** Start of the last minute with traffic. */
    private Instant lastAt;
}
