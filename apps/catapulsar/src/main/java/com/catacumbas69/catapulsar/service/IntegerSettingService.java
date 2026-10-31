package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A whole-number setting stored in {@code pulsario_settings} with a default from configuration and an
 * accepted range. The value is read from MongoDB on every call, so every replica (and other services
 * that read the same document, such as Catametricas) sees a change at once; if MongoDB cannot be read the
 * default is used. Every change is audited as {@code CHANGE_SETTING} (OBS-6) and published on the settings
 * topic (ADR 0011).
 */
public abstract class IntegerSettingService {

    private static final Logger LOG = LoggerFactory.getLogger(IntegerSettingService.class);

    private final String id;
    private final int defaultValue;
    private final int min;
    private final int max;
    private final SettingRepository settings;
    private final AuditService audit;
    private final SettingsPublisher publisher;
    private final Clock clock;

    /**
     * Creates the setting.
     *
     * @param id document id in {@code pulsario_settings}
     * @param defaultValue value used when nothing valid is stored
     * @param min smallest accepted value
     * @param max largest accepted value
     * @param settings stored settings
     * @param audit audit log
     * @param publisher publishes the value on the settings topic
     * @param clock time source
     */
    protected IntegerSettingService(String id, int defaultValue, int min, int max, SettingRepository settings,
                                    AuditService audit, SettingsPublisher publisher, Clock clock) {
        this.id = id;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.settings = settings;
        this.audit = audit;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Returns the value in use.
     *
     * @return the stored value, or the default
     */
    public int value() {
        return saved().orElse(defaultValue);
    }

    /**
     * Returns the configured default.
     *
     * @return default value
     */
    public int defaultValue() {
        return defaultValue;
    }

    /**
     * Returns the smallest accepted value.
     *
     * @return minimum
     */
    public int min() {
        return min;
    }

    /**
     * Returns the largest accepted value.
     *
     * @return maximum
     */
    public int max() {
        return max;
    }

    /**
     * Stores, audits and publishes a new value. Storing the default removes the saved value.
     *
     * @param value new value
     * @param user client IP address, for the audit log
     * @return the value now in use
     * @throws InvalidSettingException if {@code value} is out of range
     */
    public int change(int value, String user) {
        if (value < min || value > max) {
            throw new InvalidSettingException("seconds", "must be between " + min + " and " + max);
        }
        int previous = value();
        if (value == defaultValue) {
            settings.deleteById(id);
        } else {
            settings.save(new Setting(id, Integer.toString(value), clock.instant(), user));
        }
        audit.record(user, AuditAction.CHANGE_SETTING, null, Map.of("setting", id, "from", previous, "to", value));
        publisher.changed(id, value == defaultValue ? null : Integer.toString(value));
        return value;
    }

    /**
     * Goes back to the configured default.
     *
     * @param user client IP address, for the audit log
     * @return the value now in use
     */
    public int reset(String user) {
        return change(defaultValue, user);
    }

    private Optional<Integer> saved() {
        try {
            return settings.findById(id).map(Setting::getValue).flatMap(this::parse);
        } catch (RuntimeException e) {
            LOG.warn("Setting {} not read, using {}: {}", id, defaultValue, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Integer> parse(String stored) {
        try {
            int parsed = Integer.parseInt(stored.strip());
            return parsed >= min && parsed <= max ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
