package com.catacumbas69.pulsario.dto;

/**
 * UI auto-refresh interval in use.
 *
 * @param seconds interval in use
 * @param defaultSeconds configured default ({@code PULSARIO_REFRESH_INTERVAL_SECONDS})
 * @param min smallest accepted value
 * @param max largest accepted value
 * @param custom {@code true} if the interval was changed from the UI
 */
public record RefreshIntervalResponse(int seconds, int defaultSeconds, int min, int max, boolean custom) {
}
