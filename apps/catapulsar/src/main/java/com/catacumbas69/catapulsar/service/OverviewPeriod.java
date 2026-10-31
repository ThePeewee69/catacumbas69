package com.catacumbas69.catapulsar.service;

import static com.catacumbas69.catapulsar.model.CatametricasSchema.HOUR;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.HOURS;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.MINUTE;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.MINUTES;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Time ranges offered by the overviews, with the history collection and the chart step each one uses.
 * Short ranges read per-minute history (kept 30 days), long ones per-hour history (kept forever).
 */
public enum OverviewPeriod {

    /** Last hour, one point per minute. */
    LAST_HOUR("1h", Duration.ofHours(1), Duration.ofMinutes(1), MINUTES, MINUTE),
    /** Last 24 hours, one point per 15 minutes. */
    LAST_DAY("24h", Duration.ofHours(24), Duration.ofMinutes(15), MINUTES, MINUTE),
    /** Last 7 days, one point per hour. */
    LAST_WEEK("7d", Duration.ofDays(7), Duration.ofHours(1), HOURS, HOUR),
    /** Last 30 days, one point per 6 hours. */
    LAST_MONTH("30d", Duration.ofDays(30), Duration.ofHours(6), HOURS, HOUR);

    /** Accepted values of the {@code period} request parameter. */
    public static final String VALUES = "1h|24h|7d|30d";

    private final String value;
    private final Duration length;
    private final Duration step;
    private final String collection;
    private final String timeField;

    OverviewPeriod(String value, Duration length, Duration step, String collection, String timeField) {
        this.value = value;
        this.length = length;
        this.step = step;
        this.collection = collection;
        this.timeField = timeField;
    }

    /**
     * Finds a period by its request value.
     *
     * @param value one of {@value #VALUES}
     * @return the period
     * @throws IllegalArgumentException if the value is unknown
     */
    public static OverviewPeriod of(String value) {
        return Arrays.stream(values()).filter(period -> period.value.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown period " + value));
    }

    /**
     * Returns the start of every chart bucket, oldest first; the last bucket contains {@code now}.
     *
     * @param now current time
     * @return bucket starts
     */
    public List<Instant> buckets(Instant now) {
        long stepMillis = step.toMillis();
        long last = Math.floorDiv(now.toEpochMilli(), stepMillis) * stepMillis;
        int count = (int) (length.toMillis() / stepMillis);
        List<Instant> buckets = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            buckets.add(Instant.ofEpochMilli(last - i * stepMillis));
        }
        return buckets;
    }

    /**
     * Returns the request value.
     *
     * @return e.g. {@code 24h}
     */
    public String value() {
        return value;
    }

    /**
     * Returns the chart step.
     *
     * @return bucket length
     */
    public Duration step() {
        return step;
    }

    /**
     * Returns the history collection this period reads.
     *
     * @return collection name
     */
    public String collection() {
        return collection;
    }

    /**
     * Returns the time field of that collection.
     *
     * @return {@code minute} or {@code hour}
     */
    public String timeField() {
        return timeField;
    }
}
