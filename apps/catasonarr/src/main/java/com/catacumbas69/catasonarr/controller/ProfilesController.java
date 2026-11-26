package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.model.DelayProfile;
import com.catacumbas69.catasonarr.model.ReleaseProfile;
import com.catacumbas69.catasonarr.model.RemotePathMapping;
import com.catacumbas69.catasonarr.service.ProfileService;
import com.catacumbas69.catasonarr.service.RemotePathMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/releaseprofile}, {@code /api/v3/delayprofile} and {@code /api/v3/remotepathmapping}
 * (orch.md decision 122), with Sonarr's fields.
 */
@Tag(name = "Release and delay profiles, remote path mappings (Sonarr API v3)")
@RestController
public class ProfilesController {

    private final ProfileService profiles;
    private final RemotePathMappingService mappings;

    /**
     * Creates the controller.
     *
     * @param profiles release and delay profiles
     * @param mappings remote path mappings
     */
    public ProfilesController(ProfileService profiles, RemotePathMappingService mappings) {
        this.profiles = profiles;
        this.mappings = mappings;
    }

    /**
     * The release profiles.
     *
     * @return them
     */
    @Operation(summary = "The release profiles")
    @GetMapping("/api/v3/releaseprofile")
    public List<ReleaseProfile> releaseProfiles() {
        return profiles.releaseProfiles();
    }

    /**
     * A release profile.
     *
     * @param id the id
     * @return it
     */
    @Operation(summary = "A release profile")
    @GetMapping("/api/v3/releaseprofile/{id}")
    public ReleaseProfile releaseProfile(@PathVariable int id) {
        return profiles.releaseProfile(id);
    }

    /**
     * Adds a release profile.
     *
     * @param body the profile
     * @param principal who
     * @param request the request
     * @return {@code 201} with it
     */
    @Operation(summary = "Add a release profile")
    @PostMapping("/api/v3/releaseprofile")
    public ResponseEntity<ReleaseProfile> addReleaseProfile(@RequestBody ReleaseProfile body, Principal principal,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(profiles.saveReleaseProfile(0, body,
                RequestActor.of(principal, request)));
    }

    /**
     * Changes a release profile.
     *
     * @param id the id
     * @param body the profile
     * @param principal who
     * @param request the request
     * @return {@code 202} with it
     */
    @Operation(summary = "Change a release profile")
    @PutMapping("/api/v3/releaseprofile/{id}")
    public ResponseEntity<ReleaseProfile> updateReleaseProfile(@PathVariable int id, @RequestBody ReleaseProfile body,
                                                               Principal principal, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(profiles.saveReleaseProfile(id, body,
                RequestActor.of(principal, request)));
    }

    /**
     * Deletes a release profile.
     *
     * @param id the id
     * @param principal who
     * @param request the request
     * @return nothing
     */
    @Operation(summary = "Delete a release profile")
    @DeleteMapping("/api/v3/releaseprofile/{id}")
    public Map<String, Object> deleteReleaseProfile(@PathVariable int id, Principal principal,
                                                    HttpServletRequest request) {
        profiles.deleteReleaseProfile(id, RequestActor.of(principal, request));
        return Map.of();
    }

    /**
     * The delay profiles, in order; the default (id 1) is last.
     *
     * @return them
     */
    @Operation(summary = "The delay profiles")
    @GetMapping("/api/v3/delayprofile")
    public List<DelayProfile> delayProfiles() {
        return profiles.delayProfiles();
    }

    /**
     * A delay profile.
     *
     * @param id the id
     * @return it
     */
    @Operation(summary = "A delay profile")
    @GetMapping("/api/v3/delayprofile/{id}")
    public DelayProfile delayProfile(@PathVariable int id) {
        return profiles.delayProfile(id);
    }

    /**
     * Adds a delay profile.
     *
     * @param body the profile
     * @param principal who
     * @param request the request
     * @return {@code 201} with it
     */
    @Operation(summary = "Add a delay profile")
    @PostMapping("/api/v3/delayprofile")
    public ResponseEntity<DelayProfile> addDelayProfile(@RequestBody DelayProfile body, Principal principal,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(profiles.saveDelayProfile(0, body,
                RequestActor.of(principal, request)));
    }

    /**
     * Changes a delay profile.
     *
     * @param id the id
     * @param body the profile
     * @param principal who
     * @param request the request
     * @return {@code 202} with it
     */
    @Operation(summary = "Change a delay profile")
    @PutMapping("/api/v3/delayprofile/{id}")
    public ResponseEntity<DelayProfile> updateDelayProfile(@PathVariable int id, @RequestBody DelayProfile body,
                                                           Principal principal, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(profiles.saveDelayProfile(id, body,
                RequestActor.of(principal, request)));
    }

    /**
     * Deletes a delay profile (not the default).
     *
     * @param id the id
     * @param principal who
     * @param request the request
     * @return nothing
     */
    @Operation(summary = "Delete a delay profile")
    @DeleteMapping("/api/v3/delayprofile/{id}")
    public Map<String, Object> deleteDelayProfile(@PathVariable int id, Principal principal,
                                                  HttpServletRequest request) {
        profiles.deleteDelayProfile(id, RequestActor.of(principal, request));
        return Map.of();
    }

    /**
     * Moves a delay profile after another.
     *
     * @param id the profile
     * @param after the one it goes after, or none for first
     * @param principal who
     * @param request the request
     * @return the profiles in their new order
     */
    @Operation(summary = "Move a delay profile")
    @PutMapping("/api/v3/delayprofile/reorder/{id}")
    public List<DelayProfile> reorder(@PathVariable int id, @RequestParam(required = false) Integer after,
                                      Principal principal, HttpServletRequest request) {
        return profiles.reorderDelayProfile(id, after == null ? 0 : after, RequestActor.of(principal, request));
    }

    /**
     * The remote path mappings.
     *
     * @return them
     */
    @Operation(summary = "The remote path mappings")
    @GetMapping("/api/v3/remotepathmapping")
    public List<RemotePathMapping> mappings() {
        return mappings.all();
    }

    /**
     * A remote path mapping.
     *
     * @param id the id
     * @return it
     */
    @Operation(summary = "A remote path mapping")
    @GetMapping("/api/v3/remotepathmapping/{id}")
    public RemotePathMapping mapping(@PathVariable int id) {
        return mappings.get(id);
    }

    /**
     * Adds a remote path mapping.
     *
     * @param body the mapping
     * @param principal who
     * @param request the request
     * @return {@code 201} with it
     */
    @Operation(summary = "Add a remote path mapping")
    @PostMapping("/api/v3/remotepathmapping")
    public ResponseEntity<RemotePathMapping> addMapping(@RequestBody RemotePathMapping body, Principal principal,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mappings.save(0, body,
                RequestActor.of(principal, request)));
    }

    /**
     * Changes a remote path mapping.
     *
     * @param id the id
     * @param body the mapping
     * @param principal who
     * @param request the request
     * @return {@code 202} with it
     */
    @Operation(summary = "Change a remote path mapping")
    @PutMapping("/api/v3/remotepathmapping/{id}")
    public ResponseEntity<RemotePathMapping> updateMapping(@PathVariable int id, @RequestBody RemotePathMapping body,
                                                           Principal principal, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mappings.save(id, body,
                RequestActor.of(principal, request)));
    }

    /**
     * Deletes a remote path mapping.
     *
     * @param id the id
     * @param principal who
     * @param request the request
     * @return nothing
     */
    @Operation(summary = "Delete a remote path mapping")
    @DeleteMapping("/api/v3/remotepathmapping/{id}")
    public Map<String, Object> deleteMapping(@PathVariable int id, Principal principal, HttpServletRequest request) {
        mappings.delete(id, RequestActor.of(principal, request));
        return Map.of();
    }
}
