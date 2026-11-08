package com.catacumbas69.catavigia.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Clients: clock, MongoDB timeouts, Pulsar admin and binary clients, and the HTTP client for the apps.
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
    public MongoClientSettingsBuilderCustomizer mongoTimeouts(CatavigiaProperties properties) {
        CatavigiaProperties.Mongo mongo = properties.mongo();
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
    public PulsarAdmin pulsarAdmin(CatavigiaProperties properties) throws PulsarClientException {
        CatavigiaProperties.Pulsar pulsar = properties.pulsar();
        return PulsarAdmin.builder()
                .serviceHttpUrl(pulsar.serviceUrl())
                .connectionTimeout(toInt(pulsar.connectTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .readTimeout(toInt(pulsar.readTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .requestTimeout(toInt(pulsar.requestTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Binary-protocol client: check tasks, events, settings and the consumers' status (ADR 0008, ADR 0015).
     *
     * @param properties application properties
     * @return the client, closed on shutdown
     * @throws PulsarClientException if the configuration is invalid
     */
    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient(CatavigiaProperties properties) throws PulsarClientException {
        CatavigiaProperties.Pulsar pulsar = properties.pulsar();
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
     * HTTP client for the apps' health, Cataponte's bridge status and Catametricas' API, with the checks'
     * timeout (RES-2). Each call passes a full URL, since the URLs can change at runtime.
     *
     * @param properties application properties
     * @return the client
     */
    @Bean
    public RestClient appsRestClient(CatavigiaProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.checks().httpTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.checks().httpTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    private static int toInt(long millis) {
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
