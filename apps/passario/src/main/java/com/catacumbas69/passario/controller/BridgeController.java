package com.catacumbas69.passario.controller;

import com.catacumbas69.passario.dto.BridgeMapper;
import com.catacumbas69.passario.dto.BridgeRequest;
import com.catacumbas69.passario.dto.BridgeResponse;
import com.catacumbas69.passario.dto.BridgeStatusResponse;
import com.catacumbas69.passario.dto.TrafficMinuteResponse;
import com.catacumbas69.passario.dto.TrafficTotalResponse;
import com.catacumbas69.passario.service.BridgeManager;
import com.catacumbas69.passario.service.BridgeService;
import com.catacumbas69.passario.service.TrafficService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
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
 * CRUD, runtime status and traffic counts of bridges. Changes are stored and audited; the running workers
 * follow within one reconcile interval (ARCH-4).
 */
@Tag(name = "Bridges")
@RestController
@RequestMapping("/api/v1/bridges")
public class BridgeController {

    private final BridgeService service;
    private final BridgeManager manager;
    private final BridgeMapper mapper;
    private final TrafficService traffic;

    /**
     * Creates the controller.
     *
     * @param service bridge definitions
     * @param manager bridge runtime
     * @param mapper API ↔ model mapping
     * @param traffic stored message counts
     */
    public BridgeController(BridgeService service, BridgeManager manager, BridgeMapper mapper,
                            TrafficService traffic) {
        this.service = service;
        this.manager = manager;
        this.mapper = mapper;
        this.traffic = traffic;
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
     * @param http current request, for the audit log
     * @return {@code 201} with the stored bridge
     */
    @Operation(summary = "Create a bridge")
    @PostMapping
    public ResponseEntity<BridgeResponse> create(@Valid @RequestBody BridgeRequest request,
                                                 UriComponentsBuilder uriBuilder, HttpServletRequest http) {
        BridgeResponse created = mapper.toResponse(service.create(mapper.toDocument(request), ClientAddress.of(http)));
        URI location = uriBuilder.path("/api/v1/bridges/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Replaces a bridge definition. Send the {@code version} you edited to detect concurrent changes.
     *
     * @param id bridge id
     * @param request new definition
     * @param http current request, for the audit log
     * @return the stored bridge
     */
    @Operation(summary = "Update a bridge")
    @PutMapping("/{id}")
    public BridgeResponse update(@PathVariable String id, @Valid @RequestBody BridgeRequest request,
                                 HttpServletRequest http) {
        return mapper.toResponse(service.update(id, mapper.toDocument(request), request.version(),
                ClientAddress.of(http)));
    }

    /**
     * Deletes a bridge.
     *
     * @param id bridge id
     * @param http current request, for the audit log
     */
    @Operation(summary = "Delete a bridge")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest http) {
        service.delete(id, ClientAddress.of(http));
    }

    /**
     * Enables a bridge. Idempotent.
     *
     * @param id bridge id
     * @param http current request, for the audit log
     * @return the bridge
     */
    @Operation(summary = "Enable a bridge")
    @PostMapping("/{id}/enable")
    public BridgeResponse enable(@PathVariable String id, HttpServletRequest http) {
        return mapper.toResponse(service.setEnabled(id, true, ClientAddress.of(http)));
    }

    /**
     * Disables a bridge. Idempotent.
     *
     * @param id bridge id
     * @param http current request, for the audit log
     * @return the bridge
     */
    @Operation(summary = "Disable a bridge")
    @PostMapping("/{id}/disable")
    public BridgeResponse disable(@PathVariable String id, HttpServletRequest http) {
        return mapper.toResponse(service.setEnabled(id, false, ClientAddress.of(http)));
    }

    /**
     * Returns the all-time message counts of every bridge, per topic.
     *
     * @return one entry per bridge, role and topic
     */
    @Operation(summary = "All-time message counts of every bridge, per topic")
    @GetMapping("/traffic")
    public List<TrafficTotalResponse> allTraffic() {
        return traffic.allTotals().stream().map(TrafficTotalResponse::from).toList();
    }

    /**
     * Returns the all-time message counts of one bridge, per topic.
     *
     * @param id bridge id
     * @return one entry per role and topic
     */
    @Operation(summary = "All-time message counts of a bridge, per topic")
    @GetMapping("/{id}/traffic")
    public List<TrafficTotalResponse> traffic(@PathVariable String id) {
        return traffic.totals(id).stream().map(TrafficTotalResponse::from).toList();
    }

    /**
     * Returns the per-minute message counts of one bridge. Defaults to the last hour; at most 7 days.
     *
     * @param id bridge id
     * @param from first minute (ISO-8601), defaults to one hour before {@code to}
     * @param to end of the range (ISO-8601, exclusive), defaults to now
     * @return the minutes with traffic, oldest first
     */
    @Operation(summary = "Per-minute message counts of a bridge")
    @GetMapping("/{id}/traffic/minutes")
    public List<TrafficMinuteResponse> trafficMinutes(
            @PathVariable String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofHours(1));
        return traffic.minutes(id, start, end).stream().map(TrafficMinuteResponse::from).toList();
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
