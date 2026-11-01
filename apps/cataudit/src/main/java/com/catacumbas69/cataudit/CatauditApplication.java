package com.catacumbas69.cataudit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cataudit — owns the audit trail of the catacumbas69 apps: consumes their events from
 * {@code catacumbas69/cataudit/audit}, stores them in {@code cataudit_events} and serves them to Catapulsar.
 * See {@code apps/orch.md} and ADR 0009.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CatauditApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(CatauditApplication.class, args);
    }
}
