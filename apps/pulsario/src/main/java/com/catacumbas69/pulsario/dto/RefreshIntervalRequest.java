package com.catacumbas69.pulsario.dto;

import com.catacumbas69.pulsario.config.PulsarioProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * New UI auto-refresh interval.
 *
 * @param seconds interval in seconds (2–300)
 */
public record RefreshIntervalRequest(
        @NotNull
        @Min(PulsarioProperties.MIN_REFRESH_SECONDS)
        @Max(PulsarioProperties.MAX_REFRESH_SECONDS)
        Integer seconds) {
}
