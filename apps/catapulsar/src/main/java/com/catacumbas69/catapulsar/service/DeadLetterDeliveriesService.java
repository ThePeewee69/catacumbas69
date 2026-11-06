package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.consumers.DeadLetters;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Deliveries before a message goes to its dead-letter topic (2–100, ADR 0015). Edited here and read by Cataudit,
 * Catametricas and Cataponte from the settings topic (ADR 0011); each subscribes again when it changes. Default:
 * {@code catacommons.consumers.max-deliveries} (10), the same in every app.
 */
@Service
public class DeadLetterDeliveriesService extends IntegerSettingService {

    /**
     * Creates the setting.
     *
     * @param commons catacommons configuration, for the default
     * @param settings stored settings
     * @param audit audit log
     * @param publisher publishes the value on the settings topic
     * @param shared values read from the settings topic
     * @param clock time source
     */
    public DeadLetterDeliveriesService(CatacommonsProperties commons, SettingRepository settings, AuditService audit,
                                       SettingsPublisher publisher, SharedSettings shared, Clock clock) {
        super(Setting.DLQ_MAX_DELIVERIES, commons.consumers().maxDeliveries(), DeadLetters.MIN_DELIVERIES,
                DeadLetters.MAX_DELIVERIES, settings, audit, publisher, shared, clock);
    }
}
