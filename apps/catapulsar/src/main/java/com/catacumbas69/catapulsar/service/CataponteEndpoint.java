package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catapulsar.config.CatapulsarProperties;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * The Cataponte base URL used by the bridge backend-for-frontend and for the {@code audit-catapulsar} bridge
 * registration. Starts with {@code catapulsar.cataponte.url} ({@code CATAPONTE_URL}) and can be changed from
 * the UI (see {@link UrlSetting}).
 */
@Service
public class CataponteEndpoint extends UrlSetting {

    /**
     * Creates the endpoint holder.
     *
     * @param properties application properties, for the default URL
     * @param settings stored settings
     * @param audit audit log
     * @param publisher publishes the URL on the settings topic
     * @param shared values read from the settings topic
     * @param clock time source
     */
    public CataponteEndpoint(CatapulsarProperties properties, SettingRepository settings, AuditService audit,
                             SettingsPublisher publisher, SharedSettings shared, Clock clock) {
        super(Setting.CATAPONTE_URL, "Cataponte", "http://cataponte:8082", properties.cataponte().url(), settings,
                audit, publisher, shared, clock);
    }
}
