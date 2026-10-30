package com.catacumbas69.passario.config;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Creates the single {@link PulsarClient} shared by every bridge (PUL-1).
 *
 * <p>Building the client does not open a connection, so the application starts even when the broker
 * is down (RES-1); connections are made lazily with exponential back-off (RES-3).
 */
@Slf4j
@Configuration
public class PulsarClientConfig {

    /**
     * Builds the Pulsar client from {@code passario.pulsar.*}.
     *
     * @param properties application properties
     * @return the client, closed by Spring on shutdown
     * @throws PulsarClientException if the configuration is invalid
     */
    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient(PassarioProperties properties) throws PulsarClientException {
        PassarioProperties.Pulsar pulsar = properties.pulsar();
        ClientBuilder builder = PulsarClient.builder()
                .serviceUrl(pulsar.brokerUrl())
                .connectionTimeout(toMillis(pulsar.connectionTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .operationTimeout(toMillis(pulsar.operationTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .startingBackoffInterval(pulsar.startingBackoff().toMillis(), TimeUnit.MILLISECONDS)
                .maxBackoffInterval(pulsar.maxBackoff().toMillis(), TimeUnit.MILLISECONDS);
        if (StringUtils.hasText(pulsar.listenerName())) {
            builder.listenerName(pulsar.listenerName());
        }
        log.info("Pulsar client → {} (listener: {})", pulsar.brokerUrl(),
                StringUtils.hasText(pulsar.listenerName()) ? pulsar.listenerName() : "default");
        return builder.build();
    }

    private static int toMillis(long millis) {
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
