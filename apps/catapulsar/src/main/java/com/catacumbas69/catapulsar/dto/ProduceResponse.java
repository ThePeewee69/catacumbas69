package com.catacumbas69.catapulsar.dto;

/**
 * Result of producing a message.
 *
 * @param messageId id assigned by the broker
 */
public record ProduceResponse(String messageId) {
}
