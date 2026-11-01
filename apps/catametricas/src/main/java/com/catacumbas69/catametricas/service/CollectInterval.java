package com.catacumbas69.catametricas.service;

import static com.catacumbas69.catametricas.config.CatametricasProperties.MAX_INTERVAL_SECONDS;
import static com.catacumbas69.catametricas.config.CatametricasProperties.MIN_INTERVAL_SECONDS;

import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catametricas.config.CatametricasProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The collection interval. It is edited in Catapulsar and read from the settings topic, key {@value #KEY}
 * (ADR 0011), so every Catametricas instance sees a change within seconds. Until the topic has been read, and
 * when the value is missing or invalid, {@code catametricas.collect.default-interval-seconds} is used.
 */
@Component
public class CollectInterval {

    /** Key of the collection interval on the settings topic. */
    public static final String KEY = "catametricas.collect-interval-seconds";

    private static final Logger LOG = LoggerFactory.getLogger(CollectInterval.class);

    private final int defaultSeconds;
    private volatile int seconds;

    /**
     * Creates the reader and starts following the key.
     *
     * @param shared values read from the settings topic
     * @param properties application properties, for the default
     */
    public CollectInterval(SharedSettings shared, CatametricasProperties properties) {
        this.defaultSeconds = properties.collect().defaultIntervalSeconds();
        this.seconds = defaultSeconds;
        shared.listen(KEY, value -> seconds = value == null ? defaultSeconds : parse(value));
    }

    /**
     * Returns the interval to use now.
     *
     * @return seconds, between {@value CatametricasProperties#MIN_INTERVAL_SECONDS} and
     *         {@value CatametricasProperties#MAX_INTERVAL_SECONDS}
     */
    public int seconds() {
        return seconds;
    }

    private int parse(String value) {
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed >= MIN_INTERVAL_SECONDS && parsed <= MAX_INTERVAL_SECONDS) {
                LOG.info("Collection interval is now {} s", parsed);
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        LOG.warn("Ignoring collection interval '{}' (expected {}–{} s), using {} s", value, MIN_INTERVAL_SECONDS,
                MAX_INTERVAL_SECONDS, defaultSeconds);
        return defaultSeconds;
    }
}
