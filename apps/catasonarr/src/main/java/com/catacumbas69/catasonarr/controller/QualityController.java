package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.CustomFormatResource;
import com.catacumbas69.catasonarr.dto.QualityDefinitionResource;
import com.catacumbas69.catasonarr.dto.QualityProfileResource;
import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.QualityDefinition;
import com.catacumbas69.catasonarr.service.QualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/qualityprofile}, {@code /api/v3/qualitydefinition} and {@code /api/v3/customformat}
 * (orch.md decision 112).
 */
@Tag(name = "Quality (Sonarr API v3)")
@RestController
public class QualityController {

    private final QualityService quality;

    /**
     * Creates the controller.
     *
     * @param quality profiles, definitions and custom formats
     */
    public QualityController(QualityService quality) {
        this.quality = quality;
    }

    /**
     * Every quality profile.
     *
     * @return the profiles
     */
    @Operation(summary = "Every quality profile")
    @GetMapping("/api/v3/qualityprofile")
    public List<QualityProfileResource> profiles() {
        Map<Integer, String> names = formatNames();
        return quality.profiles().stream().map(p -> SonarrResources.qualityProfile(p, names)).toList();
    }

    /**
     * One quality profile.
     *
     * @param id the id
     * @return the profile
     */
    @Operation(summary = "One quality profile")
    @GetMapping("/api/v3/qualityprofile/{id}")
    public QualityProfileResource profile(@PathVariable int id) {
        return SonarrResources.qualityProfile(quality.profile(id), formatNames());
    }

    /**
     * A new profile's template: every quality, none allowed.
     *
     * @return the template
     */
    @Operation(summary = "Template of a quality profile")
    @GetMapping("/api/v3/qualityprofile/schema")
    public QualityProfileResource profileSchema() {
        return SonarrResources.qualityProfile(quality.schema(), formatNames());
    }

    /**
     * Adds a quality profile.
     *
     * @param body the profile
     * @param principal who
     * @param request the request
     * @return {@code 201} with the profile
     */
    @Operation(summary = "Add a quality profile")
    @PostMapping("/api/v3/qualityprofile")
    public ResponseEntity<QualityProfileResource> addProfile(@RequestBody QualityProfileResource body,
                                                             Principal principal, HttpServletRequest request) {
        var saved = quality.addProfile(SonarrResources.qualityProfile(body), RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.CREATED).body(SonarrResources.qualityProfile(saved, formatNames()));
    }

    /**
     * Replaces a quality profile.
     *
     * @param id the id
     * @param body the profile
     * @param principal who
     * @param request the request
     * @return {@code 202} with the profile
     */
    @Operation(summary = "Change a quality profile")
    @PutMapping("/api/v3/qualityprofile/{id}")
    public ResponseEntity<QualityProfileResource> updateProfile(@PathVariable int id,
                                                                @RequestBody QualityProfileResource body,
                                                                Principal principal, HttpServletRequest request) {
        var saved = quality.updateProfile(id, SonarrResources.qualityProfile(body),
                RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(SonarrResources.qualityProfile(saved, formatNames()));
    }

    /**
     * Deletes a quality profile no series uses.
     *
     * @param id the id
     * @param principal who
     * @param request the request
     * @return an empty object
     */
    @Operation(summary = "Delete a quality profile")
    @DeleteMapping("/api/v3/qualityprofile/{id}")
    public Map<String, Object> deleteProfile(@PathVariable int id, Principal principal, HttpServletRequest request) {
        quality.deleteProfile(id, RequestActor.of(principal, request));
        return Map.of();
    }

    /**
     * Every quality definition, worst first.
     *
     * @return the definitions
     */
    @Operation(summary = "Every quality definition")
    @GetMapping("/api/v3/qualitydefinition")
    public List<QualityDefinitionResource> definitions() {
        return quality.definitions().stream().map(SonarrResources::qualityDefinition).toList();
    }

    /**
     * One quality definition.
     *
     * @param id the id
     * @return the definition
     */
    @Operation(summary = "One quality definition")
    @GetMapping("/api/v3/qualitydefinition/{id}")
    public QualityDefinitionResource definition(@PathVariable int id) {
        return SonarrResources.qualityDefinition(quality.definition(id));
    }

    /**
     * Sonarr's limits of the size sliders.
     *
     * @return {@code {"min": 0, "max": 1000}}
     */
    @Operation(summary = "Limits of the sizes")
    @GetMapping("/api/v3/qualitydefinition/limits")
    public Map<String, Integer> limits() {
        return Map.of("min", 0, "max", 1000);
    }

    /**
     * Changes one quality definition.
     *
     * @param id the id
     * @param body the definition
     * @param principal who
     * @param request the request
     * @return {@code 202} with the definition
     */
    @Operation(summary = "Change a quality definition")
    @PutMapping("/api/v3/qualitydefinition/{id}")
    public ResponseEntity<QualityDefinitionResource> updateDefinition(@PathVariable int id,
                                                                      @RequestBody QualityDefinitionResource body,
                                                                      Principal principal,
                                                                      HttpServletRequest request) {
        List<QualityDefinition> saved = quality.updateDefinitions(List.of(definition(id, body)),
                RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(SonarrResources.qualityDefinition(saved.get(0)));
    }

    /**
     * Changes many quality definitions.
     *
     * @param body the definitions
     * @param principal who
     * @param request the request
     * @return {@code 202} with the definitions
     */
    @Operation(summary = "Change quality definitions")
    @PutMapping("/api/v3/qualitydefinition/update")
    public ResponseEntity<List<QualityDefinitionResource>> updateDefinitions(
            @RequestBody List<QualityDefinitionResource> body, Principal principal, HttpServletRequest request) {
        List<QualityDefinition> saved = quality.updateDefinitions(body.stream().map(d -> definition(d.id(), d))
                .toList(), RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(saved.stream().map(SonarrResources::qualityDefinition).toList());
    }

    /**
     * Every custom format.
     *
     * @return the formats
     */
    @Operation(summary = "Every custom format")
    @GetMapping("/api/v3/customformat")
    public List<CustomFormatResource> formats() {
        return quality.formats().stream().map(SonarrResources::customFormat).toList();
    }

    /**
     * One custom format.
     *
     * @param id the id
     * @return the format
     */
    @Operation(summary = "One custom format")
    @GetMapping("/api/v3/customformat/{id}")
    public CustomFormatResource format(@PathVariable int id) {
        return SonarrResources.customFormat(quality.format(id));
    }

    /**
     * The kinds of condition a custom format can have.
     *
     * @return one empty condition of each kind
     */
    @Operation(summary = "Kinds of custom format condition")
    @GetMapping("/api/v3/customformat/schema")
    public List<CustomFormatResource.Specification> formatSchema() {
        return SonarrResources.customFormatSchema();
    }

    /**
     * Adds a custom format.
     *
     * @param body the format
     * @param principal who
     * @param request the request
     * @return {@code 201} with the format
     */
    @Operation(summary = "Add a custom format")
    @PostMapping("/api/v3/customformat")
    public ResponseEntity<CustomFormatResource> addFormat(@RequestBody CustomFormatResource body, Principal principal,
                                                          HttpServletRequest request) {
        CustomFormat saved = quality.addFormat(SonarrResources.customFormat(body), RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.CREATED).body(SonarrResources.customFormat(saved));
    }

    /**
     * Replaces a custom format.
     *
     * @param id the id
     * @param body the format
     * @param principal who
     * @param request the request
     * @return {@code 202} with the format
     */
    @Operation(summary = "Change a custom format")
    @PutMapping("/api/v3/customformat/{id}")
    public ResponseEntity<CustomFormatResource> updateFormat(@PathVariable int id,
                                                             @RequestBody CustomFormatResource body,
                                                             Principal principal, HttpServletRequest request) {
        CustomFormat saved = quality.updateFormat(id, SonarrResources.customFormat(body),
                RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(SonarrResources.customFormat(saved));
    }

    /**
     * Deletes a custom format and its scores.
     *
     * @param id the id
     * @param principal who
     * @param request the request
     * @return an empty object
     */
    @Operation(summary = "Delete a custom format")
    @DeleteMapping("/api/v3/customformat/{id}")
    public Map<String, Object> deleteFormat(@PathVariable int id, Principal principal, HttpServletRequest request) {
        quality.deleteFormat(id, RequestActor.of(principal, request));
        return Map.of();
    }

    private Map<Integer, String> formatNames() {
        Map<Integer, String> names = new LinkedHashMap<>();
        quality.formats().forEach(f -> names.put(f.id(), f.name()));
        return names;
    }

    private static QualityDefinition definition(int id, QualityDefinitionResource r) {
        return new QualityDefinition(id, r.quality() == null ? 0 : r.quality().id(), r.title(), r.minSize(),
                r.maxSize(), r.preferredSize());
    }
}
