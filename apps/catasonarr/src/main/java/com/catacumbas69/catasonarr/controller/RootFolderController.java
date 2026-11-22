package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.RootFolderResource;
import com.catacumbas69.catasonarr.service.RootFolderService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/rootfolder} (orch.md decision 112): configurable as in Sonarr; paths inside the pod.
 */
@Tag(name = "Root folders (Sonarr API v3)")
@RestController
@RequestMapping("/api/v3/rootfolder")
public class RootFolderController {

    private final RootFolderService folders;

    /**
     * Creates the controller.
     *
     * @param folders root folders
     */
    public RootFolderController(RootFolderService folders) {
        this.folders = folders;
    }

    /**
     * Every root folder, with free space and unmapped sub-folders.
     *
     * @return the folders
     */
    @Operation(summary = "Every root folder")
    @GetMapping
    public List<RootFolderResource> all() {
        return folders.describe(folders.all(), true).stream().map(SonarrResources::rootFolder).toList();
    }

    /**
     * One root folder.
     *
     * @param id the id
     * @return the folder
     */
    @Operation(summary = "One root folder")
    @GetMapping("/{id}")
    public RootFolderResource get(@PathVariable int id) {
        return SonarrResources.rootFolder(folders.describe(List.of(folders.get(id)), true).get(0));
    }

    /**
     * Adds a root folder.
     *
     * @param body {@code {"path": "/data/TV"}}
     * @param principal who
     * @param request the request
     * @return {@code 201} with the folder
     */
    @Operation(summary = "Add a root folder")
    @PostMapping
    public ResponseEntity<RootFolderResource> add(@RequestBody RootFolderResource body, Principal principal,
                                                  HttpServletRequest request) {
        var folder = folders.add(body.path(), RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SonarrResources.rootFolder(folders.describe(List.of(folder), false).get(0)));
    }

    /**
     * Removes a root folder; its series and files stay.
     *
     * @param id the id
     * @param principal who
     * @param request the request
     * @return an empty object
     */
    @Operation(summary = "Remove a root folder")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable int id, Principal principal, HttpServletRequest request) {
        folders.delete(id, RequestActor.of(principal, request));
        return Map.of();
    }
}
