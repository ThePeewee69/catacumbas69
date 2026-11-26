package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.service.SettingsService;
import com.catacumbas69.catasonarr.service.SonarrImport;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catasonarr's own import from Sonarr's database (orch.md decision 112), under {@code /api/v3} so the script on the
 * Mac ({@code microshift/apps/catacumbas69-apps/catasonarr/catasonarr-import-sonarr.sh}) uses Sonarr's API key.
 * Not part of Sonarr's API.
 */
@Tag(name = "Import from Sonarr (Catasonarr)")
@RestController
public class ImportController {

    private final SonarrImport sonarrImport;
    private final SettingsService settings;

    /**
     * Creates the controller.
     *
     * @param sonarrImport the importer
     * @param settings where Sonarr's general settings go
     */
    public ImportController(SonarrImport sonarrImport, SettingsService settings) {
        this.sonarrImport = sonarrImport;
        this.settings = settings;
    }

    /**
     * Imports from Sonarr's tables: quality definitions, custom formats, quality profiles and the naming; then the
     * general settings, the qBittorrent and Transmission clients, release and delay profiles, remote path mappings
     * and their tags (orch.md decisions 112 and 122).
     *
     * @param rows {@code qualityDefinitions}, {@code customFormats}, {@code qualityProfiles}, {@code naming},
     *             {@code config}, {@code downloadClients}, {@code releaseProfiles}, {@code delayProfiles},
     *             {@code remotePathMappings}, {@code tags}
     * @param principal who
     * @param request the request
     * @return what was imported
     */
    @Operation(summary = "Import from Sonarr's database (Catasonarr's own)")
    @PostMapping({"/api/v3/catasonarr/import/sonarr", "/api/v3/catasonarr/import/sonarr-quality"})
    public Map<String, Object> importSonarr(@RequestBody JsonNode rows, Principal principal,
                                            HttpServletRequest request) {
        String actor = RequestActor.of(principal, request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quality", sonarrImport.importQuality(rows, actor));
        result.put("settings", sonarrImport.importSettings(rows, settings, actor));
        return result;
    }
}
