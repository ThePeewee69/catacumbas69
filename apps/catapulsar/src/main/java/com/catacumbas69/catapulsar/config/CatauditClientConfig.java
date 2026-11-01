package com.catacumbas69.catapulsar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for Cataudit, used by the audit view's backend-for-frontend (ADR 0009), with explicit timeouts
 * (RES-2).
 */
@Configuration
public class CatauditClientConfig {

    /**
     * Client for calls to Cataudit.
     *
     * @param properties application properties
     * @return a client with timeouts set; {@code AuditProxyService} adds the URL
     */
    @Bean
    public RestClient catauditRestClient(CatapulsarProperties properties) {
        CatapulsarProperties.Cataudit cataudit = properties.cataudit();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(cataudit.connectTimeout());
        requestFactory.setReadTimeout(cataudit.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
