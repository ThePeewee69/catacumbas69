package com.catacumbas69.pulsario.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description served at {@code /v3/api-docs} and {@code /swagger-ui.html} (API-6).
 */
@Configuration
public class OpenApiConfig {

    /**
     * API metadata.
     *
     * @return the OpenAPI model
     */
    @Bean
    public OpenAPI pulsarioOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Pulsário API")
                .version("v1")
                .description("Browse and manage Pulsar tenants, namespaces, topics, subscriptions and messages; "
                        + "manage Passário bridges."));
    }
}
