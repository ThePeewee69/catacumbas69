package com.catacumbas69.catapulsar.model;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A runtime setting changed from the UI, stored in Catapulsar's {@value #COLLECTION} collection and published
 * on the settings topic (ADR 0011). Settings override the matching value from {@code application.properties};
 * deleting one restores the default. The document id is the setting key on the topic.
 */
@Document(collection = Setting.COLLECTION)
public class Setting {

    /** Catapulsar's settings collection (ADR 0010; {@code pulsario_settings} before phase 3). */
    public static final String COLLECTION = "catapulsar_settings";

    /** The collection before phase 3, copied and dropped by {@code SettingsMigration}. */
    public static final String LEGACY_COLLECTION = "pulsario_settings";

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

    /** Ids in {@value #LEGACY_COLLECTION} → ids now. */
    private static final Map<String, String> LEGACY_IDS = Map.of(
            "passario.url", CATAPONTE_URL,
            CATAUDIT_URL, CATAUDIT_URL,
            "ui.refresh-interval-seconds", REFRESH_INTERVAL_SECONDS,
            "contario.collect-interval-seconds", COLLECT_INTERVAL_SECONDS);

    /** Every shared setting. */
    private static final Set<String> IDS = Set.of(CATAPONTE_URL, CATAUDIT_URL, CATAMETRICAS_URL,
            REFRESH_INTERVAL_SECONDS, COLLECT_INTERVAL_SECONDS);

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

    /**
     * Maps an id of {@value #LEGACY_COLLECTION} to the id it has now.
     *
     * @param legacyId id in the old collection
     * @return the current id, or empty for an unknown id
     */
    public static Optional<String> fromLegacyId(String legacyId) {
        return Optional.ofNullable(LEGACY_IDS.get(legacyId));
    }
}
