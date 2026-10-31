package com.catacumbas69.pulsario.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A runtime setting changed from the UI, stored in the {@code pulsario_settings} collection. Settings
 * override the matching value from {@code application.properties}; deleting one restores the default.
 */
@Document(collection = "pulsario_settings")
public class Setting {

    /** Id of the Passário base URL setting. */
    public static final String PASSARIO_URL = "passario.url";

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
     * @param id setting id, e.g. {@link #PASSARIO_URL}
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
}
