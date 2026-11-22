package com.catacumbas69.catasonarr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Catasonarr: a TV series manager that speaks Sonarr's API v3, so Prowlarr, qBittorrent, Transmission, Bazarr,
 * Overseerr, Jellyseerr and Homarr work with it as they do with Sonarr (orch.md decision 109). Its periodic work (the
 * 12-hourly metadata refresh) runs in its own pod with {@code @Scheduled}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CatasonarrApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(CatasonarrApplication.class, args);
    }
}
