package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.download.DownloadClientService;
import com.catacumbas69.catasonarr.model.DownloadClient;
import com.catacumbas69.catasonarr.providers.ProviderResources;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/downloadclient} (orch.md decision 122): qBittorrent and Transmission, each with the
 * category catasonarr. Secrets are kept in MongoDB and shown as {@code ********}.
 */
@Tag(name = "Download clients (Sonarr API v3)")
@RestController
@RequestMapping("/api/v3/downloadclient")
public class DownloadClientController {

    private final DownloadClientService clients;

    /**
     * Creates the controller.
     *
     * @param clients the download clients
     */
    public DownloadClientController(DownloadClientService clients) {
        this.clients = clients;
    }

    /**
     * Every one.
     *
     * @return them
     */
    @Operation(summary = "Every one")
    @GetMapping
    public List<Map<String, Object>> list() {
        return clients.all().stream().map(ProviderResources::client).toList();
    }

    /**
     * One.
     *
     * @param id the id
     * @return it
     */
    @Operation(summary = "One")
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable int id) {
        return ProviderResources.client(clients.get(id));
    }

    /**
     * The kinds that can be added, with their fields.
     *
     * @return the schema
     */
    @Operation(summary = "The kinds that can be added")
    @GetMapping("/schema")
    public List<Map<String, Object>> schema() {
        return ProviderResources.clientSchema();
    }

    /**
     * Adds one; it is tested first unless {@code forceSave}.
     *
     * @param body Sonarr's resource
     * @param forceSave save even when the test fails
     * @param principal who
     * @param request the request
     * @return {@code 201} with it
     */
    @Operation(summary = "Add one")
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> body,
                                                   @RequestParam(defaultValue = "false") boolean forceSave,
                                                   Principal principal, HttpServletRequest request) {
        DownloadClient added = clients.add(body, forceSave, RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ProviderResources.client(added));
    }

    /**
     * Changes one; it is tested first unless {@code forceSave}. A secret sent as {@code ********} is kept.
     *
     * @param id the id
     * @param body Sonarr's resource
     * @param forceSave save even when the test fails
     * @param principal who
     * @param request the request
     * @return {@code 202} with it
     */
    @Operation(summary = "Change one")
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable int id, @RequestBody Map<String, Object> body,
                                                      @RequestParam(defaultValue = "false") boolean forceSave,
                                                      Principal principal, HttpServletRequest request) {
        DownloadClient saved = clients.update(id, body, forceSave, RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ProviderResources.client(saved));
    }

    /**
     * Deletes one.
     *
     * @param id the id
     * @param principal who
     * @param request the request
     * @return nothing
     */
    @Operation(summary = "Delete one")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable int id, Principal principal, HttpServletRequest request) {
        clients.delete(id, RequestActor.of(principal, request));
        return Map.of();
    }

    /**
     * Tests one, not saved.
     *
     * @param body Sonarr's resource
     * @return nothing when it works; {@code 400} with why when not
     */
    @Operation(summary = "Test one, not saved")
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Map<String, Object> body) {
        clients.test(body);
        return Map.of();
    }

    /**
     * Tests every one saved.
     *
     * @return for each, whether it works and why not
     */
    @Operation(summary = "Test every one")
    @PostMapping("/testall")
    public List<Map<String, Object>> testAll() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (DownloadClient each : clients.all()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", each.id());
            try {
                clients.test(each);
                r.put("isValid", true);
                r.put("validationFailures", List.of());
            } catch (RuntimeException e) {
                r.put("isValid", false);
                r.put("validationFailures", List.of(Map.of("propertyName", "", "errorMessage",
                        String.valueOf(e.getMessage()), "severity", "error")));
            }
            results.add(r);
        }
        return results;
    }
}
