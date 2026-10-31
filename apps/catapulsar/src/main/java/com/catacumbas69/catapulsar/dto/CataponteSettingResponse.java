package com.catacumbas69.catapulsar.dto;

/**
 * Cataponte base URL in use.
 *
 * @param url URL calls go to now
 * @param defaultUrl URL from configuration ({@code CATAPONTE_URL})
 * @param custom {@code true} if the URL was changed from the UI
 */
public record CataponteSettingResponse(String url, String defaultUrl, boolean custom) {
}
