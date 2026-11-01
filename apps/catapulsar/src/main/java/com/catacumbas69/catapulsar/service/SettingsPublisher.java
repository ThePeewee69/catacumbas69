package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catacommons.settings.SettingValue;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Publishes the shared settings on the compacted topic {@code catacumbas69/catapulsar/settings} (ADR 0011):
 * key = setting id, value = {@link SettingValue}, and an empty message (tombstone) when the setting is back to
 * its default. Every service reads the topic through catacommons' {@code SharedSettings}.
 *
 * <p>Every value is published when it changes and once at start-up, so the topic always matches
 * {@code catapulsar_settings}. At start-up the old {@code pulsario_settings} is migrated first
 * ({@link SettingsMigration}); both are retried every 30 s while MongoDB is down. Publishing is asynchronous and
 * retried; it never fails the caller.
 */
@Service
public class SettingsPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsPublisher.class);
    private static final Duration RETRY = Duration.ofSeconds(30);

    private final RetryingSender<SettingValue> sender;
    private final SettingRepository settings;
    private final SettingsMigration migration;

    /**
     * Creates the publisher.
     *
     * @param sender sends to the settings topic
     * @param settings stored settings, read at start-up
     * @param migration moves {@code pulsario_settings} to {@code catapulsar_settings} first
     */
    public SettingsPublisher(RetryingSender<SettingValue> sender, SettingRepository settings,
                             SettingsMigration migration) {
        this.sender = sender;
        this.settings = settings;
        this.migration = migration;
    }

    /**
     * Publishes a setting's new value.
     *
     * @param id setting id (its key on the topic)
     * @param value the stored value, or {@code null} when the setting went back to its default
     */
    public void changed(String id, String value) {
        if (!Setting.ids().contains(id)) {
            LOG.warn("Setting {} is not a shared setting, not published", id);
            return;
        }
        sender.send(id, value == null ? null : new SettingValue(value));
    }

    /** Migrates and publishes every setting once the application has started, in the background. */
    @EventListener(ApplicationReadyEvent.class)
    public void publishAllInBackground() {
        Thread.ofVirtual().name("settings-publisher").start(this::publishAllUntilDone);
    }

    private void publishAllUntilDone() {
        while (!publishAll()) {
            try {
                Thread.sleep(RETRY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Migrates the old collection, then publishes the stored value, or a tombstone, of every shared setting.
     * Never throws.
     *
     * @return {@code false} if MongoDB could not be read (nothing was published)
     */
    boolean publishAll() {
        Map<String, String> stored = new HashMap<>();
        try {
            migration.migrate();
            settings.findAll().forEach(setting -> stored.put(setting.getId(), setting.getValue()));
        } catch (RuntimeException e) {
            LOG.warn("Settings not published yet, MongoDB not read; retrying in {} s: {}", RETRY.toSeconds(),
                    e.getMessage());
            return false;
        }
        Setting.ids().forEach(id -> changed(id, stored.get(id)));
        LOG.info("Published {} setting(s) to the settings topic ({} saved)", Setting.ids().size(), stored.size());
        return true;
    }
}
