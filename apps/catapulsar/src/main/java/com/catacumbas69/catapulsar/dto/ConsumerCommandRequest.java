package com.catacumbas69.catapulsar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/consumers/commands}.
 *
 * @param app {@code cataudit} or {@code catametricas}
 * @param instance a replica, or {@code *} for all
 * @param consumer a consumer of the app, or {@code *} for all
 * @param action {@code pause}, {@code resume} or {@code check}
 */
public record ConsumerCommandRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]*$") String app,
        @NotBlank @Size(max = 200) String instance,
        @NotBlank @Size(max = 100) String consumer,
        @NotBlank @Pattern(regexp = "^(pause|resume|check)$") String action) {
}
