package com.catacumbas69.cataudit.controller;

import com.catacumbas69.cataudit.config.CatauditProperties;
import com.catacumbas69.cataudit.dto.AuditEventResponse;
import com.catacumbas69.cataudit.dto.AuditFacetsResponse;
import com.catacumbas69.cataudit.dto.AuditPageResponse;
import com.catacumbas69.cataudit.service.AuditPage;
import com.catacumbas69.cataudit.service.AuditQuery;
import com.catacumbas69.cataudit.service.AuditStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only audit API (ADR 0009). Catapulsar's audit view calls it through its backend-for-frontend.
 */
@Tag(name = "Audit")
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditStore store;
    private final CatauditProperties.Api limits;

    /**
     * Creates the controller.
     *
     * @param store audit storage
     * @param properties application properties, for the page limits
     */
    public AuditController(AuditStore store, CatauditProperties properties) {
        this.store = store;
        this.limits = properties.api();
    }

    /**
     * Searches the audit trail, newest first. Every filter is optional.
     *
     * @param app app name, exact
     * @param entity entity, exact (e.g. {@code topic})
     * @param action action, exact (e.g. {@code created})
     * @param subject start of the subject
     * @param actor actor, exact
     * @param from first instant (ISO-8601), included
     * @param to end of the range (ISO-8601), excluded
     * @param page zero-based page
     * @param size entries per page; capped at the configured maximum
     * @return the page
     */
    @Operation(summary = "Search the audit trail")
    @GetMapping
    public AuditPageResponse search(@RequestParam(required = false) String app,
                                    @RequestParam(required = false) String entity,
                                    @RequestParam(required = false) String action,
                                    @RequestParam(required = false) String subject,
                                    @RequestParam(required = false) String actor,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(required = false) Integer size) {
        int pageSize = Math.min(Math.max(size == null ? limits.defaultPageSize() : size, 1), limits.maxPageSize());
        AuditPage result = store.find(new AuditQuery(app, entity, action, subject, actor, from, to,
                Math.max(page, 0), pageSize));
        return new AuditPageResponse(result.items().stream().map(AuditEventResponse::from).toList(), result.page(),
                result.size(), result.total());
    }

    /**
     * Returns one entry.
     *
     * @param id event id
     * @return the entry
     */
    @Operation(summary = "Get one audit entry")
    @GetMapping("/{id}")
    public AuditEventResponse get(@PathVariable String id) {
        return store.get(id).map(AuditEventResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No audit entry " + id));
    }

    /**
     * Returns the apps, entities and actions in use, for the filter lists.
     *
     * @return the values
     */
    @Operation(summary = "Values in use, for filters")
    @GetMapping("/facets")
    public AuditFacetsResponse facets() {
        return new AuditFacetsResponse(store.distinct("app"), store.distinct("entity"), store.distinct("action"));
    }
}
