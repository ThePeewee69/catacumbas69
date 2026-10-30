package com.catacumbas69.passario.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class PulsarClientConfig {

    @Value("${pulsar.broker-url}")
    private String brokerUrl;

    /** Advertised listener — "external" when running outside the cluster (Mac/IntelliJ); empty inside a pod. */
    @Value("${pulsar.listener-name:}")
    private String listenerName;

    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient() throws PulsarClientException {
        ClientBuilder builder = PulsarClient.builder().serviceUrl(brokerUrl);
        if (listenerName != null && !listenerName.isBlank()) {
            builder.listenerName(listenerName);
        }
        log.info("[Passário] Pulsar client → {} (listener: {})", brokerUrl,
                listenerName == null || listenerName.isBlank() ? "default" : listenerName);
        return builder.build();
    }
}
