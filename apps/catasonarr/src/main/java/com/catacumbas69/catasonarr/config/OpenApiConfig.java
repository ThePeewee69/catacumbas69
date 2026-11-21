package com.catacumbas69.catasonarr.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
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
    public OpenAPI catasonarrOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Catasonarr API")
                .version("v1")
                .description("Sonarr's API v3 under /api/v3 (X-Api-Key), as Sonarr's clients use it, and Catasonarr's "
                        + "own API under /api/v1.")
                .license(new License().name("GPL-3.0").url("https://www.gnu.org/licenses/gpl-3.0.txt")));
    }
}
