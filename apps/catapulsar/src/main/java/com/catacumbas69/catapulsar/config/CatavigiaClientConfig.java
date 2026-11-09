package com.catacumbas69.catapulsar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for Catavigia, used by the alerts' backend-for-frontend (ADR 0015), with explicit timeouts (RES-2).
 */
@Configuration
public class CatavigiaClientConfig {

    /**
     * Client for calls to Catavigia.
     *
     * @param properties application properties
     * @return a client with timeouts set; {@code AlertProxyService} adds the URL
     */
    @Bean
    public RestClient catavigiaRestClient(CatapulsarProperties properties) {
        CatapulsarProperties.Catavigia catavigia = properties.catavigia();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(catavigia.connectTimeout());
        requestFactory.setReadTimeout(catavigia.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
