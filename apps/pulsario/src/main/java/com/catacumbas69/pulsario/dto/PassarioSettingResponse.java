package com.catacumbas69.pulsario.dto;

/**
 * Passário base URL in use.
 *
 * @param url URL calls go to now
 * @param defaultUrl URL from configuration ({@code PASSARIO_URL})
 * @param custom {@code true} if the URL was changed from the UI
 */
public record PassarioSettingResponse(String url, String defaultUrl, boolean custom) {
}
