package com.catacumbas69.cataponte.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/bridges/{id}/skip}.
 *
 * @param messageId the message the bridge reports it is stuck on ({@code stuckMessageId} in its status)
 */
public record SkipRequest(@NotBlank @Size(max = 100) String messageId) {
}
