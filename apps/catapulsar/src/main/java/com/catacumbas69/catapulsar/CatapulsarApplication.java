package com.catacumbas69.catapulsar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Catapulsar — web console for Apache Pulsar and for Cataponte bridges. See {@code apps/orch.md}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CatapulsarApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(CatapulsarApplication.class, args);
    }
}
