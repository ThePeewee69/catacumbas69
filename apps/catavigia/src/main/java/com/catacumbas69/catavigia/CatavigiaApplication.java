package com.catacumbas69.catavigia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Catavigia: watches the catacumbas69 platform and keeps the history of every alert (ADR 0015). It only watches;
 * the owner of each resource fixes it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CatavigiaApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(CatavigiaApplication.class, args);
    }
}
