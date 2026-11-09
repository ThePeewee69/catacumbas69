package com.catacumbas69.catapulsar.model;

import com.catacumbas69.catacommons.alerts.AlertSetting;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A runtime setting changed from the UI, stored in Catapulsar's {@value #COLLECTION} collection and published
 * on the settings topic (ADR 0011). Settings override the matching value from {@code application.properties};
 * deleting one restores the default. The document id is the setting key on the topic.
 */
@Document(collection = Setting.COLLECTION)
public class Setting {

    /** Catapulsar's settings collection (ADR 0010). */
    public static final String COLLECTION = "catapulsar_settings";

    /** Cataponte base URL. */
    public static final String CATAPONTE_URL = "catapulsar.cataponte-url";

    /** Cataudit base URL (ADR 0009). */
    public static final String CATAUDIT_URL = "catapulsar.cataudit-url";

    /** Catametricas base URL (ADR 0013). */
    public static final String CATAMETRICAS_URL = "catapulsar.catametricas-url";

    /** UI auto-refresh interval, in seconds. */
    public static final String REFRESH_INTERVAL_SECONDS = "catapulsar.refresh-interval-seconds";

    /** Catametricas' collection interval, in seconds; Catametricas reads it from the settings topic. */
    public static final String COLLECT_INTERVAL_SECONDS = "catametricas.collect-interval-seconds";

    /** Catavigia base URL (ADR 0015). */
    public static final String CATAVIGIA_URL = "catapulsar.catavigia-url";

    /**
     * Deliveries before a message goes to its DLQ, read by Cataudit, Catametricas, Cataponte and Catavigia
     * (ADR 0015).
     */
    public static final String DLQ_MAX_DELIVERIES = "dlq.max-deliveries";

    /** Every shared setting. */
    private static final Set<String> IDS = Stream.concat(
            Stream.of(CATAPONTE_URL, CATAUDIT_URL, CATAMETRICAS_URL, CATAVIGIA_URL, REFRESH_INTERVAL_SECONDS,
                    COLLECT_INTERVAL_SECONDS, DLQ_MAX_DELIVERIES),
            AlertSetting.all().stream().map(AlertSetting::key)).collect(Collectors.toUnmodifiableSet());

    @Id
    private String id;

    private String value;

    private Instant updatedAt;

    /** Client IP address of whoever changed it (the application has no authentication yet). */
    private String updatedBy;

    /** For Spring Data. */
    public Setting() {
    }

    /**
     * Creates a setting.
     *
     * @param id setting id, e.g. {@link #CATAPONTE_URL}
     * @param value new value
     * @param updatedAt when it was changed
     * @param updatedBy who changed it
     */
    public Setting(String id, String value, Instant updatedAt, String updatedBy) {
        this.id = id;
        this.value = value;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /**
     * Returns the setting id.
     *
     * @return id
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the value.
     *
     * @return value
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns when the setting was changed.
     *
     * @return timestamp
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Returns who changed the setting.
     *
     * @return client IP address
     */
    public String getUpdatedBy() {
        return updatedBy;
    }

    /**
     * Returns the id of every shared setting; each is published on the settings topic.
     *
     * @return the ids (= topic keys)
     */
    public static Set<String> ids() {
        return IDS;
    }
}
