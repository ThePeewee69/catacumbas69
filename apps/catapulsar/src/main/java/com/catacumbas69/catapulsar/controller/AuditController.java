package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catapulsar.service.AuditProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail for the UI, read from Cataudit (ADR 0009). Same paths and payloads as Cataudit's
 * {@code /api/v1/audit}; see Cataudit's OpenAPI for the model.
 */
@Tag(name = "Audit")
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    /** Query parameters passed on to Cataudit; anything else is dropped. */
    static final Set<String> FILTERS = Set.of("app", "entity", "action", "subject", "actor", "from", "to", "page",
            "size");

    private final AuditProxyService audit;

    /**
     * Creates the controller.
     *
     * @param audit Cataudit client
     */
    public AuditController(AuditProxyService audit) {
        this.audit = audit;
    }

    /**
     * Searches the audit trail, newest first: {@code app}, {@code entity}, {@code action}, {@code subject}
     * (prefix), {@code actor}, {@code from}/{@code to} (ISO-8601), {@code page}, {@code size}.
     *
     * @param params query parameters
     * @return Cataudit's response
     */
    @Operation(summary = "Search the audit trail (Cataudit)")
    @GetMapping
    public ResponseEntity<byte[]> search(@RequestParam MultiValueMap<String, String> params) {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        params.forEach((name, values) -> {
            if (FILTERS.contains(name)) {
                values.stream().filter(value -> value != null && !value.isBlank())
                        .forEach(value -> filters.add(name, value));
            }
        });
        return audit.search(filters);
    }

    /**
     * Returns the apps, entities and actions in use, for the filter lists.
     *
     * @return Cataudit's response
     */
    @Operation(summary = "Values in use, for filters (Cataudit)")
    @GetMapping("/facets")
    public ResponseEntity<byte[]> facets() {
        return audit.facets();
    }

    /**
     * Returns one audit entry.
     *
     * @param id event id
     * @return Cataudit's response
     */
    @Operation(summary = "Get one audit entry (Cataudit)")
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable String id) {
        return audit.get(id);
    }
}
