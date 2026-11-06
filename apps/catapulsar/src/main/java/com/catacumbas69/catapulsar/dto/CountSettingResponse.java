package com.catacumbas69.catapulsar.dto;

import com.catacumbas69.catapulsar.service.IntegerSettingService;

/**
 * A count setting in use, e.g. the deliveries before a DLQ.
 *
 * @param value value in use
 * @param defaultValue configured default
 * @param min smallest accepted value
 * @param max largest accepted value
 * @param custom {@code true} if it was changed from the UI
 */
public record CountSettingResponse(int value, int defaultValue, int min, int max, boolean custom) {

    /**
     * Describes a setting with the given value in use.
     *
     * @param setting the setting
     * @param value value in use
     * @return the response record
     */
    public static CountSettingResponse of(IntegerSettingService setting, int value) {
        return new CountSettingResponse(value, setting.defaultValue(), setting.min(), setting.max(),
                value != setting.defaultValue());
    }
}
