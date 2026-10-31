package com.catacumbas69.pulsario.controller;

import com.catacumbas69.pulsario.dto.PulsarName;
import com.catacumbas69.pulsario.service.BridgeProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.MediaType;
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
 * Bridge management for the UI, forwarded to Passário (ARCH-2, ADR 0001). Same paths and payloads as
 * Passário's {@code /api/v1/bridges}; see Passário's OpenAPI for the bridge model.
 */
@Tag(name = "Bridges")
@RestController
@RequestMapping("/api/v1/bridges")
public class BridgeProxyController {

    private final BridgeProxyService bridges;

    /**
     * Creates the controller.
     *
     * @param bridges Passário client
     */
    public BridgeProxyController(BridgeProxyService bridges) {
        this.bridges = bridges;
    }

    /**
     * Lists bridges.
     *
     * @return Passário's response
     */
    @Operation(summary = "List bridges")
    @GetMapping
    public ResponseEntity<byte[]> list() {
        return bridges.list();
    }

    /**
     * Returns the runtime status of the bridges.
     *
     * @return Passário's response
     */
    @Operation(summary = "Runtime status of the bridges")
    @GetMapping("/status")
    public ResponseEntity<byte[]> status() {
        return bridges.status();
    }

    /**
     * Returns the all-time message counts of every bridge, per topic.
     *
     * @return Passário's response
     */
    @Operation(summary = "All-time message counts of every bridge, per topic")
    @GetMapping("/traffic")
    public ResponseEntity<byte[]> traffic() {
        return bridges.traffic();
    }

    /**
     * Returns the all-time message counts of one bridge, per topic.
     *
     * @param id bridge id
     * @return Passário's response
     */
    @Operation(summary = "All-time message counts of a bridge, per topic")
    @GetMapping("/{id}/traffic")
    public ResponseEntity<byte[]> traffic(@PathVariable @PulsarName String id) {
        return bridges.traffic(id);
    }

    /**
     * Returns the per-minute message counts of one bridge (default: last hour; at most 7 days).
     *
     * @param id bridge id
     * @param from first minute (ISO-8601), optional
     * @param to end of the range (ISO-8601, exclusive), optional
     * @return Passário's response
     */
    @Operation(summary = "Per-minute message counts of a bridge")
    @GetMapping("/{id}/traffic/minutes")
    public ResponseEntity<byte[]> trafficMinutes(@PathVariable @PulsarName String id,
                                                 @RequestParam(required = false) Instant from,
                                                 @RequestParam(required = false) Instant to) {
        return bridges.trafficMinutes(id, from, to);
    }

    /**
     * Returns one bridge.
     *
     * @param id bridge id
     * @return Passário's response
     */
    @Operation(summary = "Get a bridge")
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable @PulsarName String id) {
        return bridges.get(id);
    }

    /**
     * Creates a bridge.
     *
     * @param body bridge definition (JSON)
     * @param request current request; its client address is sent to Passário for the audit log
     * @return Passário's response
     */
    @Operation(summary = "Create a bridge")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> create(@RequestBody String body, HttpServletRequest request) {
        return bridges.create(body, ClientAddress.of(request));
    }

    /**
     * Replaces a bridge definition.
     *
     * @param id bridge id
     * @param body bridge definition (JSON)
     * @param request current request; its client address is sent to Passário for the audit log
     * @return Passário's response
     */
    @Operation(summary = "Update a bridge")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> update(@PathVariable @PulsarName String id, @RequestBody String body,
                                         HttpServletRequest request) {
        return bridges.update(id, body, ClientAddress.of(request));
    }

    /**
     * Deletes a bridge.
     *
     * @param id bridge id
     * @param request current request; its client address is sent to Passário for the audit log
     * @return Passário's response
     */
    @Operation(summary = "Delete a bridge")
    @DeleteMapping("/{id}")
    public ResponseEntity<byte[]> delete(@PathVariable @PulsarName String id, HttpServletRequest request) {
        return bridges.delete(id, ClientAddress.of(request));
    }

    /**
     * Enables a bridge.
     *
     * @param id bridge id
     * @param request current request; its client address is sent to Passário for the audit log
     * @return Passário's response
     */
    @Operation(summary = "Enable a bridge")
    @PostMapping("/{id}/enable")
    public ResponseEntity<byte[]> enable(@PathVariable @PulsarName String id, HttpServletRequest request) {
        return bridges.setEnabled(id, true, ClientAddress.of(request));
    }

    /**
     * Disables a bridge.
     *
     * @param id bridge id
     * @param request current request; its client address is sent to Passário for the audit log
     * @return Passário's response
     */
    @Operation(summary = "Disable a bridge")
    @PostMapping("/{id}/disable")
    public ResponseEntity<byte[]> disable(@PathVariable @PulsarName String id, HttpServletRequest request) {
        return bridges.setEnabled(id, false, ClientAddress.of(request));
    }
}
