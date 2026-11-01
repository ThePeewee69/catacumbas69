package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.config.CatapulsarProperties;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * The Cataudit base URL used by the audit view's backend-for-frontend (ADR 0009). Starts with
 * {@code catapulsar.cataudit.url} ({@code CATAUDIT_URL}) and can be changed from the UI (see {@link UrlSetting}).
 */
@Service
public class CatauditEndpoint extends UrlSetting {

    /**
     * Creates the endpoint holder.
     *
     * @param properties application properties, for the default URL
     * @param settings stored settings
     * @param audit audit log
     * @param publisher publishes the URL on the settings topic
     * @param clock time source
     */
    public CatauditEndpoint(CatapulsarProperties properties, SettingRepository settings, AuditService audit,
                            SettingsPublisher publisher, Clock clock) {
        super(Setting.CATAUDIT_URL, "Cataudit", "http://cataudit:8084", properties.cataudit().url(), settings, audit,
                publisher, clock);
    }
}
