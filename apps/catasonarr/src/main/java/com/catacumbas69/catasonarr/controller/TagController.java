package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.TagResource;
import com.catacumbas69.catasonarr.service.TagService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/tag}: Overseerr reads and adds its tags here.
 */
@Tag(name = "Tags (Sonarr API v3)")
@RestController
@RequestMapping("/api/v3/tag")
public class TagController {

    private final TagService tags;

    /**
     * Creates the controller.
     *
     * @param tags tags
     */
    public TagController(TagService tags) {
        this.tags = tags;
    }

    /**
     * Every tag.
     *
     * @return the tags
     */
    @Operation(summary = "Every tag")
    @GetMapping
    public List<TagResource> all() {
        return tags.all().stream().map(SonarrResources::tag).toList();
    }

    /**
     * One tag.
     *
     * @param id the id
     * @return the tag
     */
    @Operation(summary = "One tag")
    @GetMapping("/{id}")
    public TagResource get(@PathVariable int id) {
        return SonarrResources.tag(tags.get(id));
    }

    /**
     * Adds a tag, or returns the one with that label.
     *
     * @param body {@code {"label": "…"}}
     * @param principal who
     * @param request the request
     * @return {@code 201} with the tag
     */
    @Operation(summary = "Add a tag")
    @PostMapping
    public ResponseEntity<TagResource> add(@RequestBody TagResource body, Principal principal,
                                           HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SonarrResources.tag(tags.add(body.label(), RequestActor.of(principal, request))));
    }

    /**
     * Renames a tag.
     *
     * @param id the id
     * @param body {@code {"label": "…"}}
     * @param principal who
     * @param request the request
     * @return {@code 202} with the tag
     */
    @Operation(summary = "Rename a tag")
    @PutMapping("/{id}")
    public ResponseEntity<TagResource> update(@PathVariable int id, @RequestBody TagResource body,
                                              Principal principal, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(SonarrResources.tag(tags.update(id, body.label(), RequestActor.of(principal, request))));
    }

    /**
     * Deletes a tag no series uses.
     *
     * @param id the id
     * @param principal who
     * @param request the request
     * @return an empty object
     */
    @Operation(summary = "Delete a tag")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable int id, Principal principal, HttpServletRequest request) {
        tags.delete(id, RequestActor.of(principal, request));
        return Map.of();
    }
}
