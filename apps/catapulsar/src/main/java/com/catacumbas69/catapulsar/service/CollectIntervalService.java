package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.config.CatapulsarProperties;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * How often Catametricas collects Pulsar statistics, in seconds (15–600). Edited here, read by Catametricas
 * from the same {@code pulsario_settings} document (ADR 0007). Default:
 * {@code catapulsar.catametricas.collect-interval-seconds}, which must match Catametricas' own default
 * ({@code CATAMETRICAS_COLLECT_INTERVAL_SECONDS}).
 */
@Service
public class CollectIntervalService extends IntegerSettingService {

    /**
     * Creates the setting.
     *
     * @param properties application properties, for the default
     * @param settings stored settings
     * @param audit audit log
     * @param publisher publishes the value on the settings topic
     * @param clock time source
     */
    public CollectIntervalService(CatapulsarProperties properties, SettingRepository settings, AuditService audit,
                                  SettingsPublisher publisher, Clock clock) {
        super(Setting.COLLECT_INTERVAL_SECONDS, properties.catametricas().collectIntervalSeconds(),
                CatapulsarProperties.MIN_COLLECT_SECONDS, CatapulsarProperties.MAX_COLLECT_SECONDS,
                settings, audit, publisher, clock);
    }
}
