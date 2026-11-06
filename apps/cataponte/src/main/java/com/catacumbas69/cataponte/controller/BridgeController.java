package com.catacumbas69.cataponte.controller;

import com.catacumbas69.cataponte.dto.BridgeMapper;
import com.catacumbas69.cataponte.dto.BridgeRequest;
import com.catacumbas69.cataponte.dto.BridgeResponse;
import com.catacumbas69.cataponte.dto.BridgeStatusResponse;
import com.catacumbas69.cataponte.dto.SkipRequest;
import com.catacumbas69.cataponte.dto.TrafficMinuteResponse;
import com.catacumbas69.cataponte.dto.TrafficTotalResponse;
import com.catacumbas69.cataponte.model.AreaDocument;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.service.AreaFilter;
import com.catacumbas69.cataponte.service.AreaService;
import com.catacumbas69.cataponte.service.BridgeStatusService;
import com.catacumbas69.cataponte.service.BridgeService;
import com.catacumbas69.cataponte.service.BridgeValidationException;
import com.catacumbas69.cataponte.service.TrafficService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.pulsar.client.admin.PulsarAdminException;
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

    /** Header in which an app names itself when registering a system bridge. */
    static final String APP_HEADER = "X-Catacumbas69-App";

    private final BridgeService service;
    private final BridgeStatusService statuses;
    private final BridgeMapper mapper;
    private final TrafficService traffic;
    private final AreaService areas;

    /**
     * Creates the controller.
     *
     * @param service bridge definitions
     * @param statuses runtime status of the bridges over every replica
     * @param mapper API ↔ model mapping
     * @param traffic stored message counts
     * @param areas areas, to report topics outside a bridge's area (ADR 0014)
     */
    public BridgeController(BridgeService service, BridgeStatusService statuses, BridgeMapper mapper,
                            TrafficService traffic, AreaService areas) {
        this.service = service;
        this.statuses = statuses;
        this.mapper = mapper;
        this.traffic = traffic;
        this.areas = areas;
    }

    /**
     * Lists every bridge, sorted by name.
     *
     * @return all bridges
     */
    @Operation(summary = "List bridges")
    @GetMapping
    public List<BridgeResponse> list() {
        Map<String, AreaDocument> byId = areas.byId();
        return service.findAll().stream()
                .map(bridge -> respond(bridge, bridge.getAreaId() == null ? null : byId.get(bridge.getAreaId())))
                .toList();
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
        return respond(service.get(id));
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
        BridgeResponse created = respond(service.create(mapper.toDocument(request), ClientAddress.of(http)));
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
        return respond(service.update(id, mapper.toDocument(request), request.version(),
                ClientAddress.of(http)));
    }

    /**
     * Creates or updates a system bridge by name (ADR 0009). Apps call this at start-up to register their own
     * bridges, such as {@code audit-<app>}; it is idempotent and keeps an enabled state changed by a user. The
     * calling app names itself in {@code X-Catacumbas69-App}, which becomes the actor of the audit event.
     *
     * @param name bridge name; must match {@code name} in the body
     * @param request the definition
     * @param http current request, for the actor
     * @return the stored bridge
     */
    @Operation(summary = "Create or update a system bridge by name")
    @PutMapping("/system/{name}")
    public BridgeResponse registerSystem(@PathVariable String name, @Valid @RequestBody BridgeRequest request,
                                         HttpServletRequest http) {
        if (!name.equals(request.name())) {
            throw new BridgeValidationException("name", "must match the name in the path");
        }
        String app = http.getHeader(APP_HEADER);
        String actor = app != null && !app.isBlank() ? app.strip() : ClientAddress.of(http);
        return respond(service.registerSystem(mapper.toDocument(request), actor));
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
        return respond(service.setEnabled(id, true, ClientAddress.of(http)));
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
        return respond(service.setEnabled(id, false, ClientAddress.of(http)));
    }

    /**
     * Skips the message an ordered {@code Failover} bridge is stuck on (orch.md decision 69). Only if it is still the
     * first unacknowledged message of the subscription ({@code 409} otherwise). Audited as
     * {@code bridge.message-skipped}.
     *
     * @param id bridge id
     * @param request the message id from the bridge's status
     * @param http current request, for the audit log
     * @throws PulsarAdminException if Pulsar refuses
     */
    @Operation(summary = "Skip the message an ordered Failover bridge is stuck on")
    @PostMapping("/{id}/skip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skip(@PathVariable String id, @Valid @RequestBody SkipRequest request, HttpServletRequest http)
            throws PulsarAdminException {
        service.skipStuckMessage(id, request.messageId(), ClientAddress.of(http));
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
     * Returns the runtime status of every running, starting, failed or blocked bridge, over every replica.
     *
     * @return runtime status
     */
    @Operation(summary = "Runtime status of the bridges")
    @GetMapping("/status")
    public BridgeStatusResponse status() {
        return statuses.status();
    }

    private BridgeResponse respond(BridgeDocument bridge) {
        AreaDocument area = null;
        if (bridge.getAreaId() != null) {
            area = areas.byId().get(bridge.getAreaId());
        }
        return respond(bridge, area);
    }

    /** A bridge whose area no longer exists has no topics outside it. */
    private BridgeResponse respond(BridgeDocument bridge, AreaDocument area) {
        return mapper.toResponse(bridge, area == null ? List.of() : AreaFilter.of(area).outside(bridge));
    }
}
