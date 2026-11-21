package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.config.CatasonarrProperties;
import com.catacumbas69.catasonarr.dto.SystemStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/system} (orch.md decision 109): what Sonarr's clients call to test the connection.
 */
@Tag(name = "System (Sonarr API v3)")
@RestController
@RequestMapping("/api/v3/system")
public class SystemController {

    private final CatasonarrProperties.Compat compat;
    private final Instant buildTime;
    private final Instant startTime;

    /**
     * Creates the controller.
     *
     * @param properties application properties
     * @param build build information, when the jar has it ({@code build-info} goal)
     * @param clock the clock; this instance's start time is read when the controller is created
     */
    public SystemController(CatasonarrProperties properties, ObjectProvider<BuildProperties> build, Clock clock) {
        this.compat = properties.compat();
        BuildProperties info = build.getIfAvailable();
        this.buildTime = info == null ? null : info.getTime();
        this.startTime = clock.instant();
    }

    /**
     * The status, with Sonarr's field names.
     *
     * @return the status
     */
    @Operation(summary = "Status, as Sonarr answers it (connection test of Sonarr's clients)")
    @GetMapping("/status")
    public SystemStatusResponse status() {
        String os = System.getProperty("os.name", "");
        String lower = os.toLowerCase(Locale.ROOT);
        return new SystemStatusResponse("Catasonarr", compat.instanceName(), compat.version(), buildTime,
                false, true, false, false,
                System.getProperty("user.dir", ""), "mongodb",
                os, System.getProperty("os.version", ""),
                lower.contains("linux"), lower.contains("mac"), lower.contains("windows"), true,
                "console", "main", "external", "",
                "java", Runtime.version().toString(), startTime, "mongoDB");
    }
}
