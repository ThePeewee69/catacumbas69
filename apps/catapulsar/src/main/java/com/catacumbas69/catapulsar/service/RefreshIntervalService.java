package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.config.CatapulsarProperties;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * How often the UI refreshes what is on screen, in seconds (2–300), shared by every user. Default:
 * {@code catapulsar.ui.refresh-interval-seconds}.
 */
@Service
public class RefreshIntervalService extends IntegerSettingService {

    /**
     * Creates the setting.
     *
     * @param properties application properties, for the default
     * @param settings stored settings
     * @param audit audit log
     * @param clock time source
     */
    public RefreshIntervalService(CatapulsarProperties properties, SettingRepository settings, AuditService audit,
                                  Clock clock) {
        super(Setting.REFRESH_INTERVAL_SECONDS, properties.ui().refreshIntervalSeconds(),
                CatapulsarProperties.MIN_REFRESH_SECONDS, CatapulsarProperties.MAX_REFRESH_SECONDS,
                settings, audit, clock);
    }
}
