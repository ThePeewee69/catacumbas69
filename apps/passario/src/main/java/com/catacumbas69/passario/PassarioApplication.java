package com.catacumbas69.passario;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Passário — runs Pulsar topic bridges described in the MongoDB collection {@code bridges}.
 *
 * <p>Each enabled bridge consumes one source topic and fans every message out to one or more
 * destination topics. See {@code apps/orch.md} for the architecture rules.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PassarioApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(PassarioApplication.class, args);
    }
}
