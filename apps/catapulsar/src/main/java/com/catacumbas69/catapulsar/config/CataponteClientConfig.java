package com.catacumbas69.catapulsar.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for Cataponte, used by the bridge backend-for-frontend (ARCH-2), with explicit timeouts
 * (RES-2).
 */
@Configuration
@EnableConfigurationProperties(CatapulsarProperties.class)
public class CataponteClientConfig {

    /**
     * Client for calls to Cataponte.
     *
     * @param properties application properties
     * @return a client with timeouts set; the URL is taken per call from {@code CataponteEndpoint}, because
     *         it can be changed at runtime
     */
    @Bean
    public RestClient cataponteRestClient(CatapulsarProperties properties) {
        CatapulsarProperties.Cataponte cataponte = properties.cataponte();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(cataponte.connectTimeout());
        requestFactory.setReadTimeout(cataponte.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
