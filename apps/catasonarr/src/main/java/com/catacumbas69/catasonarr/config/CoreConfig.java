package com.catacumbas69.catasonarr.config;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Clients: clock, MongoDB timeouts, Pulsar admin and binary clients, TVmaze and TMDB.
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
    public MongoClientSettingsBuilderCustomizer mongoTimeouts(CatasonarrProperties properties) {
        CatasonarrProperties.Mongo mongo = properties.mongo();
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
    public PulsarAdmin pulsarAdmin(CatasonarrProperties properties) throws PulsarClientException {
        CatasonarrProperties.Pulsar pulsar = properties.pulsar();
        return PulsarAdmin.builder()
                .serviceHttpUrl(pulsar.serviceUrl())
                .connectionTimeout(toInt(pulsar.connectTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .readTimeout(toInt(pulsar.readTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .requestTimeout(toInt(pulsar.requestTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Binary-protocol client: the events (ADR 0008).
     *
     * @param properties application properties
     * @return the client, closed on shutdown
     * @throws PulsarClientException if the configuration is invalid
     */
    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient(CatasonarrProperties properties) throws PulsarClientException {
        CatasonarrProperties.Pulsar pulsar = properties.pulsar();
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
     * HTTP client for TVmaze (orch.md decision 112). It follows redirects: TVmaze answers a lookup by TheTVDB's id
     * with a redirect to the show.
     *
     * @param builder Spring Boot's builder, with its JSON converters
     * @param properties address and timeouts
     * @return the client
     */
    @Bean
    public RestClient tvmazeRestClient(RestClient.Builder builder, CatasonarrProperties properties) {
        CatasonarrProperties.Metadata metadata = properties.metadata();
        return builder.clone()
                .baseUrl(metadata.tvmazeUrl())
                .requestFactory(requestFactory(metadata))
                .defaultHeader(HttpHeaders.USER_AGENT, "Catasonarr (https://github.com/ThePeewee69/catacumbas69)")
                .build();
    }

    /**
     * HTTP client for TMDB (orch.md decision 112), with the API Read Access Token when there is one.
     *
     * @param builder Spring Boot's builder, with its JSON converters
     * @param properties address, token and timeouts
     * @return the client
     */
    @Bean
    public RestClient tmdbRestClient(RestClient.Builder builder, CatasonarrProperties properties) {
        CatasonarrProperties.Metadata metadata = properties.metadata();
        RestClient.Builder tmdb = builder.clone()
                .baseUrl(metadata.tmdbUrl())
                .requestFactory(requestFactory(metadata));
        if (StringUtils.hasText(metadata.tmdbToken())) {
            tmdb.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + metadata.tmdbToken().strip());
        }
        return tmdb.build();
    }

    private static JdkClientHttpRequestFactory requestFactory(CatasonarrProperties.Metadata metadata) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(metadata.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(metadata.readTimeout());
        return factory;
    }

    private static int toInt(long millis) {
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
