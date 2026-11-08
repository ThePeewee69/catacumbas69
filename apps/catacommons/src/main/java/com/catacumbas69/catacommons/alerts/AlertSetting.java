package com.catacumbas69.catacommons.alerts;

import com.catacumbas69.catacommons.settings.SharedSettings;
import java.util.List;

/**
 * One alert threshold on the settings topic (ADR 0011, ADR 0015): a whole number with a default and a range.
 * Catapulsar edits it in Settings → Alerts; Catavigia reads it before every check, so a change applies from the
 * next round.
 *
 * @param key setting key on the settings topic
 * @param defaultValue value while the topic has none, or an invalid one
 * @param min smallest accepted value
 * @param max largest accepted value
 */
public record AlertSetting(String key, long defaultValue, long min, long max) {

    /** Seconds between check rounds. */
    public static final AlertSetting CHECK_INTERVAL = new AlertSetting("alerts.check-interval", 30, 10, 600);
    /** Rounds in a row that must see a problem to open its alert, and that must not see it to close it. */
    public static final AlertSetting DEBOUNCE = new AlertSetting("alerts.debounce", 2, 1, 10);
    /** Messages in a subscription's backlog above which {@code backlog} opens, unless it has its own limit. */
    public static final AlertSetting BACKLOG_LIMIT = new AlertSetting("alerts.backlog-limit", 10_000, 1,
            1_000_000_000);
    /** Collection intervals without a sample after which {@code collection.stale} opens. */
    public static final AlertSetting COLLECTION_STALE_INTERVALS =
            new AlertSetting("alerts.collection-stale-intervals", 3, 2, 20);

    /**
     * Returns every alert threshold.
     *
     * @return the settings, in the order Settings → Alerts shows them
     */
    public static List<AlertSetting> all() {
        return List.of(BACKLOG_LIMIT, COLLECTION_STALE_INTERVALS, CHECK_INTERVAL, DEBOUNCE);
    }

    /**
     * Reads the value in use.
     *
     * @param settings the settings topic, or {@code null} when not available
     * @return the value on the topic if valid, otherwise the default
     */
    public long value(SharedSettings settings) {
        if (settings == null) {
            return defaultValue;
        }
        return settings.get(key).map(this::parse).orElse(defaultValue);
    }

    /**
     * Parses a value from the settings topic.
     *
     * @param value text on the topic
     * @return the number, or the default when it is not a number in range
     */
    public long parse(String value) {
        try {
            long parsed = Long.parseLong(value.strip());
            return accepts(parsed) ? parsed : defaultValue;
        } catch (NumberFormatException | NullPointerException e) {
            return defaultValue;
        }
    }

    /**
     * Tells whether a value is in range.
     *
     * @param value a value
     * @return {@code true} if {@code min <= value <= max}
     */
    public boolean accepts(long value) {
        return value >= min && value <= max;
    }
}
