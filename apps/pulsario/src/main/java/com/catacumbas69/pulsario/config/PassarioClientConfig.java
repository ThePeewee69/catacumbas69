package com.catacumbas69.pulsario.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for Passário, used by the bridge backend-for-frontend (ARCH-2), with explicit timeouts
 * (RES-2).
 */
@Configuration
@EnableConfigurationProperties(PulsarioProperties.class)
public class PassarioClientConfig {

    /**
     * Client for calls to Passário.
     *
     * @param properties application properties
     * @return a client with timeouts set; the URL is taken per call from {@code PassarioEndpoint}, because
     *         it can be changed at runtime
     */
    @Bean
    public RestClient passarioRestClient(PulsarioProperties properties) {
        PulsarioProperties.Passario passario = properties.passario();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(passario.connectTimeout());
        requestFactory.setReadTimeout(passario.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
