package com.catacumbas69.pulsario.service;

import static com.catacumbas69.pulsario.config.PulsarioProperties.MAX_REFRESH_SECONDS;
import static com.catacumbas69.pulsario.config.PulsarioProperties.MIN_REFRESH_SECONDS;

import com.catacumbas69.pulsario.config.PulsarioProperties;
import com.catacumbas69.pulsario.model.Setting;
import com.catacumbas69.pulsario.repository.SettingRepository;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * How often the UI refreshes what is on screen, in seconds. One value for every user.
 *
 * <p>The default comes from {@code pulsario.ui.refresh-interval-seconds}; a value changed from the UI is
 * stored in {@code pulsario_settings} and read from MongoDB on every request, so all replicas agree.
 * If MongoDB cannot be read the default is used. Every change is audited (OBS-6).
 */
@Service
public class RefreshIntervalService {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshIntervalService.class);

    private final int defaultSeconds;
    private final SettingRepository settings;
    private final AuditService audit;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param properties application properties, for the default
     * @param settings stored settings
     * @param audit audit log
     * @param clock time source
     */
    public RefreshIntervalService(PulsarioProperties properties, SettingRepository settings, AuditService audit,
                                  Clock clock) {
        this.defaultSeconds = properties.ui().refreshIntervalSeconds();
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Returns the interval in use.
     *
     * @return seconds, between {@link PulsarioProperties#MIN_REFRESH_SECONDS} and
     *         {@link PulsarioProperties#MAX_REFRESH_SECONDS}
     */
    public int seconds() {
        return saved().orElse(defaultSeconds);
    }

    /**
     * Returns the configured default.
     *
     * @return seconds
     */
    public int defaultSeconds() {
        return defaultSeconds;
    }

    /**
     * Stores and audits a new interval. Storing the default removes the saved value.
     *
     * @param seconds new interval
     * @param user client IP address, for the audit log
     * @return the interval now in use
     * @throws InvalidSettingException if {@code seconds} is out of range
     */
    public int change(int seconds, String user) {
        if (seconds < MIN_REFRESH_SECONDS || seconds > MAX_REFRESH_SECONDS) {
            throw new InvalidSettingException("seconds",
                    "must be between " + MIN_REFRESH_SECONDS + " and " + MAX_REFRESH_SECONDS);
        }
        int previous = seconds();
        if (seconds == defaultSeconds) {
            settings.deleteById(Setting.REFRESH_INTERVAL_SECONDS);
        } else {
            settings.save(new Setting(Setting.REFRESH_INTERVAL_SECONDS, Integer.toString(seconds), clock.instant(),
                    user));
        }
        audit.record(user, AuditAction.CHANGE_SETTING, null,
                Map.of("setting", Setting.REFRESH_INTERVAL_SECONDS, "from", previous, "to", seconds));
        return seconds;
    }

    /**
     * Goes back to the configured default.
     *
     * @param user client IP address, for the audit log
     * @return the interval now in use
     */
    public int reset(String user) {
        return change(defaultSeconds, user);
    }

    private Optional<Integer> saved() {
        try {
            return settings.findById(Setting.REFRESH_INTERVAL_SECONDS)
                    .map(Setting::getValue)
                    .flatMap(RefreshIntervalService::parse);
        } catch (RuntimeException e) {
            LOG.warn("Saved refresh interval not read, using {} s: {}", defaultSeconds, e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<Integer> parse(String value) {
        try {
            int seconds = Integer.parseInt(value.strip());
            boolean inRange = seconds >= MIN_REFRESH_SECONDS && seconds <= MAX_REFRESH_SECONDS;
            return inRange ? Optional.of(seconds) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
