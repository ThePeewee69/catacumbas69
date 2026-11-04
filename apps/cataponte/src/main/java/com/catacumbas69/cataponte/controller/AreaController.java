package com.catacumbas69.cataponte.controller;

import com.catacumbas69.cataponte.dto.AreaRequest;
import com.catacumbas69.cataponte.dto.AreaResponse;
import com.catacumbas69.cataponte.model.AreaDocument;
import com.catacumbas69.cataponte.service.AreaService;
import com.catacumbas69.cataponte.service.AreaService.BridgesOnDelete;
import com.catacumbas69.cataponte.service.BridgeValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Areas: named groups of bridges limited to the topics their glob patterns match (ADR 0014). Every change is
 * audited, so it reaches Cataudit.
 */
@Tag(name = "Areas")
@RestController
@RequestMapping("/api/v1/areas")
public class AreaController {

    private final AreaService service;

    /**
     * Creates the controller.
     *
     * @param service areas
     */
    public AreaController(AreaService service) {
        this.service = service;
    }

    /**
     * Lists every area with its number of bridges, sorted by name.
     *
     * @return all areas
     */
    @Operation(summary = "List areas")
    @GetMapping
    public List<AreaResponse> list() {
        Map<String, Long> counts = service.bridgeCounts();
        return service.findAll().stream()
                .map(area -> AreaResponse.of(area, counts.getOrDefault(area.getId(), 0L))).toList();
    }

    /**
     * Returns one area.
     *
     * @param id area id
     * @return the area
     */
    @Operation(summary = "Get an area")
    @GetMapping("/{id}")
    public AreaResponse get(@PathVariable String id) {
        return respond(service.get(id));
    }

    /**
     * Creates an area.
     *
     * @param request the new area
     * @param uriBuilder builder for the {@code Location} header
     * @param http current request, for the audit log
     * @return {@code 201} with the stored area
     */
    @Operation(summary = "Create an area")
    @PostMapping
    public ResponseEntity<AreaResponse> create(@Valid @RequestBody AreaRequest request,
                                               UriComponentsBuilder uriBuilder, HttpServletRequest http) {
        AreaResponse created = respond(service.create(AreaResponse.toDocument(request), ClientAddress.of(http)));
        URI location = uriBuilder.path("/api/v1/areas/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Replaces an area's name, description and patterns. Send the {@code version} you edited. Bridges whose topics
     * no longer match are kept and reported with {@code outsideArea}.
     *
     * @param id area id
     * @param request the new definition
     * @param http current request, for the audit log
     * @return the stored area
     */
    @Operation(summary = "Update an area")
    @PutMapping("/{id}")
    public AreaResponse update(@PathVariable String id, @Valid @RequestBody AreaRequest request,
                               HttpServletRequest http) {
        return respond(service.update(id, AreaResponse.toDocument(request), request.version(),
                ClientAddress.of(http)));
    }

    /**
     * Deletes an area. One that has bridges needs {@code bridges=delete} (delete them too) or {@code bridges=move}
     * (move them to {@code to}, another area, or to the general group when {@code to} is absent); otherwise
     * {@code 409}.
     *
     * @param id area id
     * @param bridges {@code delete} or {@code move}
     * @param to area to move the bridges to
     * @param http current request, for the audit log
     */
    @Operation(summary = "Delete an area")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam(required = false) String bridges,
                       @RequestParam(required = false) String to, HttpServletRequest http) {
        service.delete(id, onBridges(bridges), to, ClientAddress.of(http));
    }

    private AreaResponse respond(AreaDocument area) {
        return AreaResponse.of(area, service.bridgeCounts().getOrDefault(area.getId(), 0L));
    }

    private static BridgesOnDelete onBridges(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BridgesOnDelete.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BridgeValidationException("bridges", "must be delete or move");
        }
    }
}
