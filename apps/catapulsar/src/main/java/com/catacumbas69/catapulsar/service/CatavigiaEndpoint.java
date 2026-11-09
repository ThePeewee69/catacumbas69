package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catapulsar.config.CatapulsarProperties;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * The Catavigia base URL used by the alerts' backend-for-frontend (ADR 0015, orch.md decision 78). Starts with
 * {@code catapulsar.catavigia.url} ({@code CATAVIGIA_URL}) and can be changed from the UI (see {@link UrlSetting}).
 */
@Service
public class CatavigiaEndpoint extends UrlSetting {

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
    public CatavigiaEndpoint(CatapulsarProperties properties, SettingRepository settings, AuditService audit,
                             SettingsPublisher publisher, SharedSettings shared, Clock clock) {
        super(Setting.CATAVIGIA_URL, "Catavigia", "http://catavigia:8085", properties.catavigia().url(), settings,
                audit, publisher, shared, clock);
    }
}
