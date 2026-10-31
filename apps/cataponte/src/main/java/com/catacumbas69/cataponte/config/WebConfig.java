package com.catacumbas69.cataponte.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration. CORS is closed unless origins are configured explicitly (API-7).
 */
@Configuration
@EnableConfigurationProperties(CataponteProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final CataponteProperties properties;

    /**
     * Creates the configuration.
     *
     * @param properties application properties
     */
    public WebConfig(CataponteProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.cors().allowedOrigins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type");
    }
}
