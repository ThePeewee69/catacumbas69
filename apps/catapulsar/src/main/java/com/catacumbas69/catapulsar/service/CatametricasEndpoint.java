package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catapulsar.config.CatapulsarProperties;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * The Catametricas base URL used by the overviews (ADR 0013). Starts with {@code catapulsar.catametricas.url}
 * ({@code CATAMETRICAS_URL}) and can be changed from the UI (see {@link UrlSetting}).
 */
@Service
public class CatametricasEndpoint extends UrlSetting {

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
    public CatametricasEndpoint(CatapulsarProperties properties, SettingRepository settings, AuditService audit,
                                SettingsPublisher publisher, SharedSettings shared, Clock clock) {
        super(Setting.CATAMETRICAS_URL, "Catametricas", "http://catametricas:8083", properties.catametricas().url(),
                settings, audit, publisher, shared, clock);
    }
}
