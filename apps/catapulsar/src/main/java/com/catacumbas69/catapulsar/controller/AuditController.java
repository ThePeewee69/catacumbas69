package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catapulsar.dto.AuditEntryResponse;
import com.catacumbas69.catapulsar.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to the audit log, newest first and paginated (API-8).
 */
@Tag(name = "Audit")
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService audit;

    /**
     * Creates the controller.
     *
     * @param audit audit log
     */
    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    /**
     * Returns one page of audit entries.
     *
     * @param page zero-based page number
     * @param size entries per page (1–100)
     * @return the entries, newest first
     */
    @Operation(summary = "Recent audit entries")
    @GetMapping
    public List<AuditEntryResponse> recent(@RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return audit.recent(page, size).stream().map(AuditEntryResponse::from).toList();
    }
}
