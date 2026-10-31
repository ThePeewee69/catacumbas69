package com.catacumbas69.cataponte.dto;

import java.time.Instant;
import java.util.List;

/**
 * A bridge as returned by the API.
 *
 * @param id bridge id
 * @param name unique name
 * @param description free text
 * @param enabled whether the bridge should run
 * @param source where messages are read from
 * @param destinations where messages are copied to
 * @param preserveKey whether the message key is copied
 * @param preserveProperties whether message properties are copied
 * @param ordered whether source order is kept (effective value)
 * @param maxInFlight unconfirmed messages allowed (effective value)
 * @param version version to send back on update
 * @param createdAt creation time
 * @param updatedAt last change time
 */
public record BridgeResponse(
        String id,
        String name,
        String description,
        boolean enabled,
        Source source,
        List<Destination> destinations,
        boolean preserveKey,
        boolean preserveProperties,
        boolean ordered,
        int maxInFlight,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Source of a bridge.
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @param subscriptionType Pulsar subscription type
     */
    public record Source(String topic, String subscription, String subscriptionType) {
    }

    /**
     * One destination of a bridge.
     *
     * @param topic fully-qualified topic
     */
    public record Destination(String topic) {
    }
}
