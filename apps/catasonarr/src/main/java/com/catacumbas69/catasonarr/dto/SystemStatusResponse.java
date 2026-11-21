package com.catacumbas69.catasonarr.dto;

import java.time.Instant;

/**
 * The answer of {@code GET /api/v3/system/status}, with Sonarr's field names (orch.md decision 109). Sonarr's
 * clients call it to test the connection: Prowlarr, Overseerr and Jellyseerr read {@code version}, Homarr and
 * Bazarr {@code appName} and {@code version}. Fields that only describe Sonarr's own .NET runtime are left out.
 *
 * @param appName {@code Catasonarr}
 * @param instanceName the name configured in {@code catasonarr.compat.instance-name}
 * @param version the Sonarr version Catasonarr answers as ({@code catasonarr.compat.version})
 * @param buildTime when this build was made; {@code null} when unknown (run from the IDE)
 * @param isDebug always {@code false}
 * @param isProduction always {@code true}
 * @param isAdmin always {@code false}: never runs as root
 * @param isUserInteractive always {@code false}
 * @param startupPath the working directory
 * @param appData where the data is kept: MongoDB, not a folder
 * @param osName the operating system's name
 * @param osVersion its version
 * @param isLinux whether it runs on Linux
 * @param isOsx whether it runs on macOS
 * @param isWindows whether it runs on Windows
 * @param isDocker always {@code true}: it runs in a container
 * @param mode always {@code console}
 * @param branch always {@code main}
 * @param authentication {@code external}: the page logs in through Dex
 * @param urlBase empty: served at the root
 * @param runtimeName {@code java}
 * @param runtimeVersion the JVM's version
 * @param startTime when this instance started
 * @param databaseType {@code mongoDB}
 */
public record SystemStatusResponse(
        String appName,
        String instanceName,
        String version,
        Instant buildTime,
        boolean isDebug,
        boolean isProduction,
        boolean isAdmin,
        boolean isUserInteractive,
        String startupPath,
        String appData,
        String osName,
        String osVersion,
        boolean isLinux,
        boolean isOsx,
        boolean isWindows,
        boolean isDocker,
        String mode,
        String branch,
        String authentication,
        String urlBase,
        String runtimeName,
        String runtimeVersion,
        Instant startTime,
        String databaseType) {
}
