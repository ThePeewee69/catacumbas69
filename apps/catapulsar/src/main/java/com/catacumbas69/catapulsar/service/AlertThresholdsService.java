package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.alerts.AlertSetting;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The alert thresholds Catavigia reads (ADR 0015, Settings → Alerts): {@code alerts.backlog-limit},
 * {@code alerts.collection-stale-intervals}, {@code alerts.check-interval} and {@code alerts.debounce}, with the
 * keys, defaults and ranges of catacommons {@link AlertSetting}. Each is saved in MongoDB, audited and published on
 * the settings topic, like the other settings.
 */
@Service
public class AlertThresholdsService {

    /** Prefix of the keys, left out of the names used in the API. */
    static final String PREFIX = "alerts.";

    private final Map<String, IntegerSettingService> thresholds = new LinkedHashMap<>();

    /**
     * Creates the thresholds.
     *
     * @param settings stored settings
     * @param audit audit log
     * @param publisher publishes the values on the settings topic
     * @param shared values read from the settings topic
     * @param clock time source
     */
    public AlertThresholdsService(SettingRepository settings, AuditService audit, SettingsPublisher publisher,
                                  SharedSettings shared, Clock clock) {
        for (AlertSetting setting : AlertSetting.all()) {
            thresholds.put(nameOf(setting), new Threshold(setting, settings, audit, publisher, shared, clock));
        }
    }

    /**
     * Returns every threshold, by API name.
     *
     * @return e.g. {@code backlog-limit} to its setting, in the order Settings → Alerts shows them
     */
    public Map<String, IntegerSettingService> all() {
        return thresholds;
    }

    /**
     * Returns one threshold.
     *
     * @param name API name, e.g. {@code debounce}
     * @return the setting, if the name is known
     */
    public Optional<IntegerSettingService> get(String name) {
        return Optional.ofNullable(thresholds.get(name));
    }

    private static String nameOf(AlertSetting setting) {
        return setting.key().startsWith(PREFIX) ? setting.key().substring(PREFIX.length()) : setting.key();
    }

    /** One threshold as an integer setting. */
    private static final class Threshold extends IntegerSettingService {

        Threshold(AlertSetting setting, SettingRepository settings, AuditService audit, SettingsPublisher publisher,
                  SharedSettings shared, Clock clock) {
            super(setting.key(), (int) setting.defaultValue(), (int) setting.min(), (int) setting.max(), settings,
                    audit, publisher, shared, clock);
        }
    }
}
