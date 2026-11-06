package com.catacumbas69.catapulsar.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/v1/settings/dead-letters}.
 *
 * @param value new number; checked against the setting's range
 */
public record CountSettingRequest(@NotNull Integer value) {
}
