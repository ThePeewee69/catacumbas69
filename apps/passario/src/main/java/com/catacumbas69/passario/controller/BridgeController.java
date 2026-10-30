package com.catacumbas69.passario.controller;

import com.catacumbas69.passario.dto.BridgeMapper;
import com.catacumbas69.passario.dto.BridgeRequest;
import com.catacumbas69.passario.dto.BridgeResponse;
import com.catacumbas69.passario.dto.BridgeStatusResponse;
import com.catacumbas69.passario.service.BridgeManager;
import com.catacumbas69.passario.service.BridgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * CRUD and runtime status of bridges. Changes are stored only; the running workers follow within one
 * reconcile interval (ARCH-4).
 */
@Tag(name = "Bridges")
@RestController
@RequestMapping("/api/v1/bridges")
public class BridgeController {

    private final BridgeService service;
    private final BridgeManager manager;
    private final BridgeMapper mapper;

    /**
     * Creates the controller.
     *
     * @param service bridge definitions
     * @param manager bridge runtime
     * @param mapper API ↔ model mapping
     */
    public BridgeController(BridgeService service, BridgeManager manager, BridgeMapper mapper) {
        this.service = service;
        this.manager = manager;
        this.mapper = mapper;
    }

    /**
     * Lists every bridge, sorted by name.
     *
     * @return all bridges
     */
    @Operation(summary = "List bridges")
    @GetMapping
    public List<BridgeResponse> list() {
        return service.findAll().stream().map(mapper::toResponse).toList();
    }

    /**
     * Returns one bridge.
     *
     * @param id bridge id
     * @return the bridge
     */
    @Operation(summary = "Get a bridge")
    @GetMapping("/{id}")
    public BridgeResponse get(@PathVariable String id) {
        return mapper.toResponse(service.get(id));
    }

    /**
     * Creates a bridge.
     *
     * @param request new definition
     * @param uriBuilder builder for the {@code Location} header
     * @return {@code 201} with the stored bridge
     */
    @Operation(summary = "Create a bridge")
    @PostMapping
    public ResponseEntity<BridgeResponse> create(@Valid @RequestBody BridgeRequest request,
                                                 UriComponentsBuilder uriBuilder) {
        BridgeResponse created = mapper.toResponse(service.create(mapper.toDocument(request)));
        URI location = uriBuilder.path("/api/v1/bridges/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Replaces a bridge definition. Send the {@code version} you edited to detect concurrent changes.
     *
     * @param id bridge id
     * @param request new definition
     * @return the stored bridge
     */
    @Operation(summary = "Update a bridge")
    @PutMapping("/{id}")
    public BridgeResponse update(@PathVariable String id, @Valid @RequestBody BridgeRequest request) {
        return mapper.toResponse(service.update(id, mapper.toDocument(request), request.version()));
    }

    /**
     * Deletes a bridge.
     *
     * @param id bridge id
     */
    @Operation(summary = "Delete a bridge")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /**
     * Enables a bridge. Idempotent.
     *
     * @param id bridge id
     * @return the bridge
     */
    @Operation(summary = "Enable a bridge")
    @PostMapping("/{id}/enable")
    public BridgeResponse enable(@PathVariable String id) {
        return mapper.toResponse(service.setEnabled(id, true));
    }

    /**
     * Disables a bridge. Idempotent.
     *
     * @param id bridge id
     * @return the bridge
     */
    @Operation(summary = "Disable a bridge")
    @PostMapping("/{id}/disable")
    public BridgeResponse disable(@PathVariable String id) {
        return mapper.toResponse(service.setEnabled(id, false));
    }

    /**
     * Returns the runtime status of every running, starting or failed bridge.
     *
     * @return runtime status
     */
    @Operation(summary = "Runtime status of the bridges")
    @GetMapping("/status")
    public BridgeStatusResponse status() {
        return new BridgeStatusResponse(manager.getRunningCount(), manager.getStatuses());
    }
}
