package com.catacumbas69.catapulsar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of the {@code /api/v1/dead-letters} actions.
 *
 * @param topic the DLQ, a full topic name ending in {@code -DLQ}
 * @param messageId the message, from a peek of the DLQ; not used by the "all" actions
 */
public record DeadLetterRequest(
        @NotBlank @Pattern(regexp = "^persistent://[^/\\s]+/[^/\\s]+/[^/\\s]+-DLQ$",
                message = "must be a dead-letter topic, persistent://tenant/namespace/<topic>-DLQ") String topic,
        @Size(max = 100) String messageId) {
}
