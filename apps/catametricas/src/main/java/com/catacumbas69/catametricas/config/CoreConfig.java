package com.catacumbas69.catametricas.config;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core beans: time source, MongoDB client timeouts and the Pulsar admin client. Nothing connects at
 * start-up, so Catametricas starts even when Pulsar or MongoDB is down (RES-1).
 */
@Configuration
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
     * Explicit MongoDB timeouts (RES-2) on top of the connection string.
     *
     * @param properties application properties
     * @return the customizer
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer mongoTimeouts(CatametricasProperties properties) {
        CatametricasProperties.Mongo mongo = properties.mongo();
        return builder -> builder
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(
                        mongo.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(mongo.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(mongo.readTimeout().toMillis(), TimeUnit.MILLISECONDS));
    }

    /**
     * Admin client for the Pulsar HTTP API (PUL-1, PERF-6).
     *
     * @param properties application properties
     * @return the admin client, closed on shutdown
     * @throws PulsarClientException if the configuration is invalid
     */
    @Bean(destroyMethod = "close")
    public PulsarAdmin pulsarAdmin(CatametricasProperties properties) throws PulsarClientException {
        CatametricasProperties.Pulsar pulsar = properties.pulsar();
        return PulsarAdmin.builder()
                .serviceHttpUrl(pulsar.serviceUrl())
                .connectionTimeout(toInt(pulsar.connectTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .readTimeout(toInt(pulsar.readTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .requestTimeout(toInt(pulsar.requestTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .build();
    }

    private static int toInt(long millis) {
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
