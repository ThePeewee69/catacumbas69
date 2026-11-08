package com.catacumbas69.catavigia.controller;

import com.catacumbas69.catavigia.dto.AlertPageResponse;
import com.catacumbas69.catavigia.dto.AlertResponse;
import com.catacumbas69.catavigia.dto.AlertSummaryResponse;
import com.catacumbas69.catavigia.service.AlertQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alerts (ADR 0015): open, archived, the badge summary, and marking an alert as seen. Catapulsar forwards
 * {@code /api/v1/alerts/**}.
 */
@Tag(name = "Alerts")
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    /** Alert ids are UUIDs. */
    static final String ID = "^[0-9a-fA-F-]{36}$";

    private final AlertQueryService alerts;

    /**
     * Creates the controller.
     *
     * @param alerts reads and marks the alerts
     */
    public AlertController(AlertQueryService alerts) {
        this.alerts = alerts;
    }

    /**
     * Lists the open alerts.
     *
     * @return critical first, newest first; this replica's {@code mongodb.down} included
     */
    @Operation(summary = "Open alerts")
    @GetMapping
    public List<AlertResponse> open() {
        return alerts.open();
    }

    /**
     * Counts the open alerts not marked as seen, for the badge.
     *
     * @return the summary
     */
    @Operation(summary = "Open alerts not marked as seen, by severity")
    @GetMapping("/summary")
    public AlertSummaryResponse summary() {
        return alerts.summary();
    }

    /**
     * Lists closed alerts.
     *
     * @param type only this type
     * @param subject only subjects starting with this
     * @param from only alerts closed at or after this (ISO-8601)
     * @param to only alerts closed before this (ISO-8601)
     * @param page page number, from 0
     * @param size page size (default 50, at most 500)
     * @return newest closed first
     */
    @Operation(summary = "Archived (closed) alerts, newest closed first")
    @GetMapping("/archive")
    public AlertPageResponse archive(
            @RequestParam(required = false) @Pattern(regexp = "^[a-z.]{1,64}$") String type,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) @Min(1) @Max(1_000) Integer size) {
        return alerts.archive(type, subject, from, to, page, size);
    }

    /**
     * Returns one alert, open or archived.
     *
     * @param id the alert's id
     * @return the alert
     */
    @Operation(summary = "One alert, open or archived")
    @GetMapping("/{id}")
    public AlertResponse get(@PathVariable @Pattern(regexp = ID) String id) {
        return alerts.get(id);
    }

    /**
     * Marks an open alert as seen; published as {@code alert.seen}.
     *
     * @param id the alert's id
     * @param request the request, for who made it
     * @return the alert
     */
    @Operation(summary = "Mark an open alert as seen: it stays open and leaves the badge count")
    @PostMapping("/{id}/seen")
    public AlertResponse seen(@PathVariable @Pattern(regexp = ID) String id, HttpServletRequest request) {
        return alerts.markSeen(id, ClientAddress.of(request));
    }
}
