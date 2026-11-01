package com.catacumbas69.catapulsar.config;

import com.catacumbas69.catacommons.audit.CataponteLocation;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catacommons.pulsar.RetryingSenderFactory;
import com.catacumbas69.catacommons.pulsar.TopicSpec;
import com.catacumbas69.catacommons.settings.SettingValue;
import com.catacumbas69.catapulsar.service.CataponteEndpoint;
import org.apache.pulsar.client.api.Schema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Catapulsar's topics besides its events (which catacommons sets up): the compacted settings topic
 * {@code catacumbas69/catapulsar/settings} (ADR 0011); and where its audit bridge is registered (ADR 0009).
 */
@Configuration
public class EventsConfig {

    /**
     * Declares the settings topic, so it is created at start-up as a compacted topic with infinite
     * retention and the {@link SettingValue} schema.
     *
     * @return the topic spec
     */
    @Bean
    public TopicSpec settingsTopic() {
        return TopicSpec.compacted(CatacumbasTopics.SETTINGS, Schema.JSON(SettingValue.class));
    }

    /**
     * Sends setting values to the settings topic.
     *
     * @param factory sender factory from catacommons
     * @return the sender, closed on shutdown
     */
    @Bean(destroyMethod = "close")
    public RetryingSender<SettingValue> settingsSender(RetryingSenderFactory factory) {
        return factory.create(Schema.JSON(SettingValue.class), CatacumbasTopics.settings());
    }

    /**
     * Registers the {@code audit-catapulsar} bridge at the Cataponte URL in use, which can be changed in the UI,
     * instead of {@code CATAPONTE_URL}.
     *
     * @param endpoint Cataponte URL holder
     * @return the location
     */
    @Bean
    public CataponteLocation cataponteLocation(CataponteEndpoint endpoint) {
        return endpoint::url;
    }
}
