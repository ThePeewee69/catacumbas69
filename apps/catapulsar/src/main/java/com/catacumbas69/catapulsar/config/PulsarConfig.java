package com.catacumbas69.catapulsar.config;

import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Creates the shared {@link PulsarAdmin} and {@link PulsarClient} (PUL-1, PERF-6). Neither connects
 * when built, so the application starts even when Pulsar is down (RES-1).
 */
@Configuration
public class PulsarConfig {

    /**
     * Admin client for the Pulsar HTTP API.
     *
     * @param properties application properties
     * @return the admin client, closed on shutdown
     * @throws PulsarClientException if the configuration is invalid
     */
    @Bean(destroyMethod = "close")
    public PulsarAdmin pulsarAdmin(CatapulsarProperties properties) throws PulsarClientException {
        CatapulsarProperties.Pulsar pulsar = properties.pulsar();
        return PulsarAdmin.builder()
                .serviceHttpUrl(pulsar.serviceUrl())
                .connectionTimeout(toInt(pulsar.adminConnectTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .readTimeout(toInt(pulsar.adminReadTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .requestTimeout(toInt(pulsar.adminRequestTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Binary-protocol client used to produce messages.
     *
     * @param properties application properties
     * @return the client, closed on shutdown
     * @throws PulsarClientException if the configuration is invalid
     */
    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient(CatapulsarProperties properties) throws PulsarClientException {
        CatapulsarProperties.Pulsar pulsar = properties.pulsar();
        ClientBuilder builder = PulsarClient.builder()
                .serviceUrl(pulsar.brokerUrl())
                .connectionTimeout(toInt(pulsar.connectionTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .operationTimeout(toInt(pulsar.operationTimeout().toMillis()), TimeUnit.MILLISECONDS);
        if (StringUtils.hasText(pulsar.listenerName())) {
            builder.listenerName(pulsar.listenerName());
        }
        return builder.build();
    }

    private static int toInt(long millis) {
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
