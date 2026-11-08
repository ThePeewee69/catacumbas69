package com.catacumbas69.catavigia.controller;

import com.catacumbas69.catacommons.alerts.AlertSetting;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.dto.BacklogLimitRequest;
import com.catacumbas69.catavigia.dto.BacklogLimitResponse;
import com.catacumbas69.catavigia.dto.BacklogLimitsResponse;
import com.catacumbas69.catavigia.service.BacklogLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backlog limits per subscription (orch.md decision 70). Each change is published as {@code limit.changed};
 * Catapulsar forwards {@code /api/v1/limits/**}.
 */
@Tag(name = "Limits")
@RestController
@RequestMapping("/api/v1/limits/backlog")
public class LimitController {

    /** A topic without partition suffix, with its domain. */
    static final String TOPIC = "^(persistent|non-persistent)://[^/\\s]+/[^/\\s]+/[^/\\s]+$";
    /** A subscription name. */
    static final String SUBSCRIPTION = "^[^/\\s|]{1,255}$";

    private final BacklogLimitService limits;
    private final SharedSettings settings;

    /**
     * Creates the controller.
     *
     * @param limits stores the limits
     * @param settings the settings topic, for the global default
     */
    public LimitController(BacklogLimitService limits, ObjectProvider<SharedSettings> settings) {
        this.limits = limits;
        this.settings = settings.getIfAvailable();
    }

    /**
     * Lists the global default and every subscription's own limit.
     *
     * @return the limits
     */
    @Operation(summary = "Global default and every subscription's own backlog limit")
    @GetMapping
    public BacklogLimitsResponse list() {
        return new BacklogLimitsResponse(AlertSetting.BACKLOG_LIMIT.value(settings),
                limits.list().stream().map(BacklogLimitResponse::of).toList());
    }

    /**
     * Sets a subscription's own limit.
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @param body the limit
     * @param request the request, for who made it
     * @return the limit stored
     */
    @Operation(summary = "Set a subscription's own backlog limit")
    @PutMapping
    public BacklogLimitResponse set(@RequestParam @Pattern(regexp = TOPIC) String topic,
                                    @RequestParam @Pattern(regexp = SUBSCRIPTION) String subscription,
                                    @Valid @RequestBody BacklogLimitRequest body, HttpServletRequest request) {
        return BacklogLimitResponse.of(limits.set(topic, subscription, body.limit(), ClientAddress.of(request)));
    }

    /**
     * Removes a subscription's own limit: the global default applies again.
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @param request the request, for who made it
     * @return {@code 204}
     */
    @Operation(summary = "Remove a subscription's own backlog limit (back to the global default)")
    @DeleteMapping
    public ResponseEntity<Void> remove(@RequestParam @Pattern(regexp = TOPIC) String topic,
                                       @RequestParam @Pattern(regexp = SUBSCRIPTION) String subscription,
                                       HttpServletRequest request) {
        if (!limits.remove(topic, subscription, ClientAddress.of(request))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No own limit for " + subscription + " on "
                    + topic);
        }
        return ResponseEntity.noContent().build();
    }
}
