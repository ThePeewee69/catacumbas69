package com.catacumbas69.catametricas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Catametricas — collects Pulsar topic statistics into MongoDB so Catapulsar can show how much traffic passed
 * through each topic, namespace and tenant over time. See {@code apps/orch.md} and ADR 0007.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CatametricasApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(CatametricasApplication.class, args);
    }
}
