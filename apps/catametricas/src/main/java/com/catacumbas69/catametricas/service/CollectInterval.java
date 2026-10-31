package com.catacumbas69.catametricas.service;

import static com.catacumbas69.catametricas.config.CatametricasProperties.MAX_INTERVAL_SECONDS;
import static com.catacumbas69.catametricas.config.CatametricasProperties.MIN_INTERVAL_SECONDS;

import com.catacumbas69.catametricas.config.CatametricasProperties;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The collection interval. It is edited in Catapulsar and stored in Catapulsar's {@value #COLLECTION}
 * collection ({@code _id} {@value #SETTING_ID}, field {@code value}); Catametricas only reads it (ADR 0007).
 * Missing, invalid or unreadable values fall back to {@code catametricas.collect.default-interval-seconds}.
 */
@Component
public class CollectInterval {

    /** Catapulsar's settings collection. */
    public static final String COLLECTION = "pulsario_settings";
    /** Setting id of the collection interval. */
    public static final String SETTING_ID = "contario.collect-interval-seconds";

    private static final Logger LOG = LoggerFactory.getLogger(CollectInterval.class);

    private final MongoTemplate mongoTemplate;
    private final int defaultSeconds;

    /**
     * Creates the reader.
     *
     * @param mongoTemplate template for the settings collection
     * @param properties application properties, for the default
     */
    public CollectInterval(MongoTemplate mongoTemplate, CatametricasProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.defaultSeconds = properties.collect().defaultIntervalSeconds();
    }

    /**
     * Returns the interval to use now.
     *
     * @return seconds, between {@value CatametricasProperties#MIN_INTERVAL_SECONDS} and
     *         {@value CatametricasProperties#MAX_INTERVAL_SECONDS}
     */
    public int seconds() {
        try {
            Document setting = mongoTemplate.findById(SETTING_ID, Document.class, COLLECTION);
            if (setting == null || setting.get("value") == null) {
                return defaultSeconds;
            }
            int seconds = Integer.parseInt(setting.get("value").toString().strip());
            if (seconds < MIN_INTERVAL_SECONDS || seconds > MAX_INTERVAL_SECONDS) {
                LOG.warn("Ignoring collection interval {} s outside {}–{} s", seconds, MIN_INTERVAL_SECONDS,
                        MAX_INTERVAL_SECONDS);
                return defaultSeconds;
            }
            return seconds;
        } catch (NumberFormatException e) {
            LOG.warn("Ignoring collection interval that is not a number: {}", e.getMessage());
            return defaultSeconds;
        } catch (RuntimeException e) {
            LOG.debug("Collection interval not read, using {} s: {}", defaultSeconds, e.getMessage());
            return defaultSeconds;
        }
    }
}
