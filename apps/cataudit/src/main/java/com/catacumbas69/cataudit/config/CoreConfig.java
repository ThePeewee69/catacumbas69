package com.catacumbas69.cataudit.config;

import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.pulsar.TopicSpec;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Core beans: time source, MongoDB client timeouts, the Pulsar clients and the audit topic. Nothing connects at
 * start-up, so Cataudit starts even when Pulsar or MongoDB is down (RES-1).
 */
@Configuration
public class CoreConfig {

    /**
     * System clock in UTC.
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
    public MongoClientSettingsBuilderCustomizer mongoTimeouts(CatauditProperties properties) {
        CatauditProperties.Mongo mongo = properties.mongo();
        return builder -> builder
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(
                        mongo.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(mongo.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(mongo.readTimeout().toMillis(), TimeUnit.MILLISECONDS));
    }

    /**
     * Admin client: catacommons creates the namespace and topics with it (ADR 0008).
     *
     * @param properties application properties
     * @return the admin client, closed on shutdown
     * @throws PulsarClientException if the configuration is invalid
     */
    @Bean(destroyMethod = "close")
    public PulsarAdmin pulsarAdmin(CatauditProperties properties) throws PulsarClientException {
        CatauditProperties.Pulsar pulsar = properties.pulsar();
        return PulsarAdmin.builder()
                .serviceHttpUrl(pulsar.serviceUrl())
                .connectionTimeout(toInt(pulsar.adminConnectTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .readTimeout(toInt(pulsar.adminReadTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .requestTimeout(toInt(pulsar.adminRequestTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Binary client: consumes the audit topic.
     *
     * @param properties application properties
     * @return the client, closed on shutdown
     * @throws PulsarClientException if the configuration is invalid
     */
    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient(CatauditProperties properties) throws PulsarClientException {
        CatauditProperties.Pulsar pulsar = properties.pulsar();
        ClientBuilder builder = PulsarClient.builder()
                .serviceUrl(pulsar.brokerUrl())
                .connectionTimeout(toInt(pulsar.connectionTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .operationTimeout(toInt(pulsar.operationTimeout().toMillis()), TimeUnit.MILLISECONDS);
        if (StringUtils.hasText(pulsar.listenerName())) {
            builder.listenerName(pulsar.listenerName());
        }
        return builder.build();
    }

    /**
     * Declares {@code catacumbas69/cataudit/audit}, fed by the {@code audit-<app>} bridges, so catacommons
     * creates it at start-up with the CloudEvent schema.
     *
     * @return the topic spec
     */
    @Bean
    public TopicSpec auditTopic() {
        return TopicSpec.of(CatacumbasTopics.AUDIT, Schema.JSON(CloudEvent.class));
    }

    private static int toInt(long millis) {
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
