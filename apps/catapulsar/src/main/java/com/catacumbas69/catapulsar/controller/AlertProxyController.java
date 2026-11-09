package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catapulsar.service.AlertProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alerts and backlog limits, forwarded to Catavigia (ADR 0015). Same paths as Catavigia's API.
 */
@Tag(name = "Alerts")
@RestController
public class AlertProxyController {

    /** Alert ids are UUIDs. */
    static final String ID = "^[0-9a-fA-F-]{36}$";
    /** Query parameters the archive accepts. */
    private static final List<String> ARCHIVE_PARAMS = List.of("type", "subject", "from", "to", "page", "size");

    private final AlertProxyService alerts;

    /**
     * Creates the controller.
     *
     * @param alerts Catavigia client
     */
    public AlertProxyController(AlertProxyService alerts) {
        this.alerts = alerts;
    }

    /**
     * Lists the open alerts.
     *
     * @return Catavigia's response
     */
    @Operation(summary = "Open alerts")
    @GetMapping("/api/v1/alerts")
    public ResponseEntity<byte[]> open() {
        return alerts.get("/api/v1/alerts", null);
    }

    /**
     * Counts the open alerts not marked as seen, for the badge.
     *
     * @return Catavigia's response
     */
    @Operation(summary = "Open alerts not marked as seen, by severity")
    @GetMapping("/api/v1/alerts/summary")
    public ResponseEntity<byte[]> summary() {
        return alerts.get("/api/v1/alerts/summary", null);
    }

    /**
     * Lists closed alerts.
     *
     * @param params {@code type}, {@code subject}, {@code from}, {@code to}, {@code page}, {@code size}
     * @return Catavigia's response
     */
    @Operation(summary = "Archived alerts, newest closed first")
    @GetMapping("/api/v1/alerts/archive")
    public ResponseEntity<byte[]> archive(@RequestParam MultiValueMap<String, String> params) {
        MultiValueMap<String, String> passed = new LinkedMultiValueMap<>();
        ARCHIVE_PARAMS.forEach(name -> {
            if (params.containsKey(name)) {
                passed.put(name, params.get(name));
            }
        });
        return alerts.get("/api/v1/alerts/archive", passed);
    }

    /**
     * Returns one alert, open or archived.
     *
     * @param id alert id
     * @return Catavigia's response
     */
    @Operation(summary = "One alert")
    @GetMapping("/api/v1/alerts/{id}")
    public ResponseEntity<byte[]> get(@PathVariable @Pattern(regexp = ID) String id) {
        return alerts.get("/api/v1/alerts/" + id, null);
    }

    /**
     * Marks an alert as seen.
     *
     * @param id alert id
     * @param request current request; its client address is sent to Catavigia
     * @return Catavigia's response
     */
    @Operation(summary = "Mark an open alert as seen")
    @PostMapping("/api/v1/alerts/{id}/seen")
    public ResponseEntity<byte[]> seen(@PathVariable @Pattern(regexp = ID) String id, HttpServletRequest request) {
        return alerts.seen(id, ClientAddress.of(request));
    }

    /**
     * Lists the global default and every subscription's own backlog limit.
     *
     * @return Catavigia's response
     */
    @Operation(summary = "Backlog limits: global default and per subscription")
    @GetMapping("/api/v1/limits/backlog")
    public ResponseEntity<byte[]> limits() {
        return alerts.get("/api/v1/limits/backlog", null);
    }

    /**
     * Sets a subscription's own backlog limit.
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @param body {@code {"limit": n}}
     * @param request current request; its client address is sent to Catavigia
     * @return Catavigia's response
     */
    @Operation(summary = "Set a subscription's own backlog limit")
    @PutMapping(path = "/api/v1/limits/backlog", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> setLimit(@RequestParam String topic, @RequestParam String subscription,
                                           @RequestBody String body, HttpServletRequest request) {
        return alerts.setLimit(topic, subscription, body, ClientAddress.of(request));
    }

    /**
     * Removes a subscription's own backlog limit (back to the global default).
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @param request current request; its client address is sent to Catavigia
     * @return Catavigia's response
     */
    @Operation(summary = "Remove a subscription's own backlog limit")
    @DeleteMapping("/api/v1/limits/backlog")
    public ResponseEntity<byte[]> removeLimit(@RequestParam String topic, @RequestParam String subscription,
                                              HttpServletRequest request) {
        return alerts.removeLimit(topic, subscription, ClientAddress.of(request));
    }
}
