package com.catacumbas69.pulsario.config;

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class PulsarConfig {

    @Value("${pulsar.service-url}")
    private String serviceUrl;

    @Value("${pulsar.broker-url}")
    private String brokerUrl;

    /** Listener a usar no lookup do broker.
     *  Vazio (padrão) → acesso interno no cluster (pod IP routable).
     *  "external"     → acesso externo via NodePort 31650 (Mac/IntelliJ). */
    @Value("${pulsar.listener-name:}")
    private String listenerName;

    @Bean
    public PulsarAdmin pulsarAdmin() throws Exception {
        return PulsarAdmin.builder()
                .serviceHttpUrl(serviceUrl)
                .connectionTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .requestTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient() throws Exception {
        ClientBuilder builder = PulsarClient.builder()
                .serviceUrl(brokerUrl)
                .operationTimeout(30, TimeUnit.SECONDS)
                .connectionTimeout(30, TimeUnit.SECONDS);

        if (listenerName != null && !listenerName.isBlank()) {
            builder.listenerName(listenerName);
        }

        return builder.build();
    }
}