package com.catacumbas69.catavigia.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/v1/limits/backlog}.
 *
 * @param limit messages above which {@code backlog} opens for this subscription
 */
public record BacklogLimitRequest(@NotNull @Min(1) @Max(1_000_000_000) Long limit) {
}
