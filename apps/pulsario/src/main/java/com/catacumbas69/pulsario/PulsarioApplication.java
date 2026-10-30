package com.catacumbas69.pulsario;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Pulsário — web console for Apache Pulsar and for Passário bridges. See {@code apps/orch.md}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PulsarioApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(PulsarioApplication.class, args);
    }
}
