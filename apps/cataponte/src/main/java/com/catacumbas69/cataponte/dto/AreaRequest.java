package com.catacumbas69.cataponte.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code POST /api/v1/areas} and {@code PUT /api/v1/areas/{id}} (ADR 0014).
 *
 * @param name unique name
 * @param description optional free text
 * @param patterns glob patterns over topic names, {@code tenant/namespace/topic} without {@code persistent://};
 *                 {@code *} within a segment, {@code **} across segments; none = every topic
 * @param version version being edited, for conflict detection on update (RES-9); ignored on create
 */
public record AreaRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = ValidationPatterns.NAME,
                message = "may contain only letters, digits, '.', '-' and '_'") String name,
        @Size(max = 500) String description,
        @Size(max = 20) List<@Size(max = 300) String> patterns,
        Long version) {
}
