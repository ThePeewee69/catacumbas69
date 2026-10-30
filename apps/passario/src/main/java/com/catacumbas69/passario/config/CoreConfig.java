package com.catacumbas69.passario.config;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Core infrastructure beans: time source, scheduling, MongoDB auditing and MongoDB client timeouts.
 */
@Configuration
@EnableScheduling
@EnableMongoAuditing
public class CoreConfig {

    /**
     * System clock in UTC. Injected wherever time matters so tests can control it.
     *
     * @return the UTC clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Applies explicit MongoDB timeouts (RES-2) on top of the connection string, so an unreachable
     * database fails fast instead of blocking callers for the driver's 30 s default.
     *
     * @param properties application properties
     * @return the customizer
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer mongoTimeouts(PassarioProperties properties) {
        PassarioProperties.Mongo mongo = properties.mongo();
        return builder -> builder
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(
                        mongo.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(mongo.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(mongo.readTimeout().toMillis(), TimeUnit.MILLISECONDS));
    }
}
