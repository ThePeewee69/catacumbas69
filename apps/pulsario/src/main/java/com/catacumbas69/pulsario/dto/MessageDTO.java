package com.catacumbas69.pulsario.dto;

import java.util.Map;

/**
 * A message returned by a backlog peek.
 *
 * @param messageId Pulsar message id
 * @param payload UTF-8 text, or Base64 when {@code binary} is {@code true}
 * @param key message key, or {@code null}
 * @param publishTime publish time in epoch milliseconds
 * @param properties user-defined properties
 * @param binary {@code true} when the payload is not valid UTF-8 and is Base64-encoded
 */
public record MessageDTO(
        String messageId,
        String payload,
        String key,
        long publishTime,
        Map<String, String> properties,
        boolean binary) {
}
