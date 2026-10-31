package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catacommons.settings.SettingValue;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Publishes the shared settings on the compacted topic {@code catacumbas69/catapulsar/settings}
 * (ADR 0011): key = setting key ({@link Setting#topicKey(String)}), value = {@link SettingValue}, and an
 * empty message (tombstone) when the setting is back to its default.
 *
 * <p>Every value is published when it changes and once at start-up, so the topic always matches
 * {@code pulsario_settings}. Publishing is asynchronous and retried; it never fails the caller. Nobody reads
 * the topic yet: the services move to it in phase 3.
 */
@Service
public class SettingsPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsPublisher.class);

    private final RetryingSender<SettingValue> sender;
    private final SettingRepository settings;

    /**
     * Creates the publisher.
     *
     * @param sender sends to the settings topic
     * @param settings stored settings, read at start-up
     */
    public SettingsPublisher(RetryingSender<SettingValue> sender, SettingRepository settings) {
        this.sender = sender;
        this.settings = settings;
    }

    /**
     * Publishes a setting's new value.
     *
     * @param id document id in {@code pulsario_settings}
     * @param value the stored value, or {@code null} when the setting went back to its default
     */
    public void changed(String id, String value) {
        Setting.topicKey(id).ifPresentOrElse(
                key -> sender.send(key, value == null ? null : new SettingValue(value)),
                () -> LOG.warn("Setting {} has no key on the settings topic, not published", id));
    }

    /** Publishes every setting once the application has started, in the background. */
    @EventListener(ApplicationReadyEvent.class)
    public void publishAllInBackground() {
        Thread.ofVirtual().name("settings-publisher").start(this::publishAll);
    }

    /** Publishes the stored value, or a tombstone, of every shared setting. Never throws. */
    void publishAll() {
        Map<String, String> stored = new HashMap<>();
        try {
            settings.findAll().forEach(setting -> stored.put(setting.getId(), setting.getValue()));
        } catch (RuntimeException e) {
            LOG.warn("Settings not published at start-up, MongoDB not read: {}", e.getMessage());
            return;
        }
        Setting.publishedIds().forEach(id -> changed(id, stored.get(id)));
        LOG.info("Published {} setting(s) to the settings topic ({} saved)", Setting.publishedIds().size(),
                stored.size());
    }
}
