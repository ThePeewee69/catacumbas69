package com.catacumbas69.catapulsar.dto;

import com.catacumbas69.catapulsar.service.IntegerSettingService;

/**
 * One alert threshold (Settings → Alerts).
 *
 * @param name API name, e.g. {@code backlog-limit}
 * @param value value in use
 * @param defaultValue value after "use default"
 * @param min smallest accepted value
 * @param max largest accepted value
 * @param custom {@code true} when it differs from the default
 */
public record AlertThresholdResponse(String name, int value, int defaultValue, int min, int max, boolean custom) {

    /**
     * Describes a threshold with the given value in use.
     *
     * @param name API name
     * @param setting the setting
     * @param value value in use
     * @return the response
     */
    public static AlertThresholdResponse of(String name, IntegerSettingService setting, int value) {
        return new AlertThresholdResponse(name, value, setting.defaultValue(), setting.min(), setting.max(),
                value != setting.defaultValue());
    }
}
