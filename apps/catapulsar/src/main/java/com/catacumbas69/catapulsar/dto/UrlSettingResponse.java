package com.catacumbas69.catapulsar.dto;

import com.catacumbas69.catapulsar.service.UrlSetting;

/**
 * Base URL of another service (Cataponte, Cataudit) in use.
 *
 * @param url URL calls go to now
 * @param defaultUrl URL from configuration ({@code CATAPONTE_URL}, {@code CATAUDIT_URL})
 * @param custom {@code true} if the URL was changed from the UI
 */
public record UrlSettingResponse(String url, String defaultUrl, boolean custom) {

    /**
     * Describes a URL setting.
     *
     * @param setting the setting
     * @return its current state
     */
    public static UrlSettingResponse of(UrlSetting setting) {
        return new UrlSettingResponse(setting.url(), setting.defaultUrl(), setting.isCustom());
    }
}
