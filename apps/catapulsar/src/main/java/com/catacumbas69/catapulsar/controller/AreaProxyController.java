package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catapulsar.dto.PulsarName;
import com.catacumbas69.catapulsar.service.BridgeProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
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
 * Bridge areas for the UI (ADR 0014), forwarded to Cataponte, which validates, stores and audits them. Same paths
 * and payloads as Cataponte's {@code /api/v1/areas}.
 */
@Tag(name = "Bridges")
@RestController
@RequestMapping("/api/v1/areas")
public class AreaProxyController {

    private static final String ID = "^[A-Za-z0-9_-]{1,100}$";

    private final BridgeProxyService bridges;

    /**
     * Creates the controller.
     *
     * @param bridges Cataponte client
     */
    public AreaProxyController(BridgeProxyService bridges) {
        this.bridges = bridges;
    }

    /**
     * Lists the areas, with their number of bridges.
     *
     * @return Cataponte's response
     */
    @Operation(summary = "List bridge areas")
    @GetMapping
    public ResponseEntity<byte[]> list() {
        return bridges.listAreas();
    }

    /**
     * Creates an area.
     *
     * @param body area definition (JSON)
     * @param request current request; its client address is sent to Cataponte for the audit log
     * @return Cataponte's response
     */
    @Operation(summary = "Create a bridge area")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> create(@RequestBody String body, HttpServletRequest request) {
        return bridges.createArea(body, ClientAddress.of(request));
    }

    /**
     * Replaces an area.
     *
     * @param id area id
     * @param body area definition (JSON)
     * @param request current request; its client address is sent to Cataponte for the audit log
     * @return Cataponte's response
     */
    @Operation(summary = "Update a bridge area")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> update(@PathVariable @PulsarName String id, @RequestBody String body,
                                         HttpServletRequest request) {
        return bridges.updateArea(id, body, ClientAddress.of(request));
    }

    /**
     * Deletes an area. One that has bridges needs {@code bridges=delete} or {@code bridges=move} (to {@code to},
     * or to the general group).
     *
     * @param id area id
     * @param bridgesChoice {@code delete} or {@code move}
     * @param to area to move the bridges to
     * @param request current request; its client address is sent to Cataponte for the audit log
     * @return Cataponte's response
     */
    @Operation(summary = "Delete a bridge area")
    @DeleteMapping("/{id}")
    public ResponseEntity<byte[]> delete(@PathVariable @PulsarName String id,
                                         @RequestParam(name = "bridges", required = false)
                                         @Pattern(regexp = "^(delete|move)$") String bridgesChoice,
                                         @RequestParam(required = false) @Pattern(regexp = ID) String to,
                                         HttpServletRequest request) {
        return bridges.deleteArea(id, bridgesChoice, to, ClientAddress.of(request));
    }
}
