package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.service.SonarrImport;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
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

    /**
     * Creates the controller.
     *
     * @param sonarrImport the importer
     */
    public ImportController(SonarrImport sonarrImport) {
        this.sonarrImport = sonarrImport;
    }

    /**
     * Imports quality definitions, custom formats, quality profiles and the folder naming from Sonarr's tables.
     *
     * @param rows {@code qualityDefinitions}, {@code customFormats}, {@code qualityProfiles}, {@code naming}
     * @param principal who
     * @param request the request
     * @return what was imported
     */
    @Operation(summary = "Import quality and naming from Sonarr's database (Catasonarr's own)")
    @PostMapping("/api/v3/catasonarr/import/sonarr-quality")
    public SonarrImport.Result importQuality(@RequestBody JsonNode rows, Principal principal,
                                             HttpServletRequest request) {
        return sonarrImport.importQuality(rows, RequestActor.of(principal, request));
    }
}
