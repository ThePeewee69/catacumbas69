package com.catacumbas69.catapulsar.dto;

import com.catacumbas69.catapulsar.service.IntegerSettingService;

/**
 * An interval setting in use.
 *
 * @param seconds interval in use
 * @param defaultSeconds configured default
 * @param min smallest accepted value
 * @param max largest accepted value
 * @param custom {@code true} if the interval was changed from the UI
 */
public record IntervalSettingResponse(int seconds, int defaultSeconds, int min, int max, boolean custom) {

    /**
     * Describes a setting with the given value in use.
     *
     * @param setting the setting
     * @param seconds value in use
     * @return the response record
     */
    public static IntervalSettingResponse of(IntegerSettingService setting, int seconds) {
        return new IntervalSettingResponse(seconds, setting.defaultValue(), setting.min(), setting.max(),
                seconds != setting.defaultValue());
    }
}
