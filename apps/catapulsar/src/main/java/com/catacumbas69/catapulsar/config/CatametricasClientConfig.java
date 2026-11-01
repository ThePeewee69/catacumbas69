package com.catacumbas69.catapulsar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for Catametricas, used by the overviews (ADR 0013), with explicit timeouts (RES-2).
 */
@Configuration
public class CatametricasClientConfig {

    /**
     * Client for calls to Catametricas.
     *
     * @param properties application properties
     * @return a client with timeouts set; {@code CatametricasClient} adds the URL
     */
    @Bean
    public RestClient catametricasRestClient(CatapulsarProperties properties) {
        CatapulsarProperties.Catametricas catametricas = properties.catametricas();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(catametricas.connectTimeout());
        requestFactory.setReadTimeout(catametricas.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
