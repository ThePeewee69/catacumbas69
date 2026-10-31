package com.catacumbas69.catapulsar.dto;

import jakarta.validation.constraints.NotNull;

/**
 * New value of an interval setting. The accepted range depends on the setting and is checked by the
 * service (the error names the field {@code seconds}).
 *
 * @param seconds interval in seconds
 */
public record IntervalSettingRequest(@NotNull Integer seconds) {
}
