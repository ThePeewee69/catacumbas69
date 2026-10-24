package com.catacumbas69.pulsarclient.config;

import org.apache.pulsar.client.admin.PulsarAdmin;
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
        return PulsarClient.builder()
                .serviceUrl(brokerUrl)
                .operationTimeout(30, TimeUnit.SECONDS)
                .connectionTimeout(30, TimeUnit.SECONDS)
                .build();
    }
}
