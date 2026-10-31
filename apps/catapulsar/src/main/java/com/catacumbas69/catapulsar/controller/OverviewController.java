package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catapulsar.dto.OverviewResponse;
import com.catacumbas69.catapulsar.dto.PulsarName;
import com.catacumbas69.catapulsar.service.OverviewPeriod;
import com.catacumbas69.catapulsar.service.OverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cluster, tenant and namespace overviews from Catametricas' statistics: current state, traffic in the
 * chosen period, all-time traffic and chart series. Reads MongoDB only.
 */
@Tag(name = "Overview")
@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {

    private final OverviewService overview;

    /**
     * Creates the controller.
     *
     * @param overview overview builder
     */
    public OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    /**
     * Overview of the cluster, one row per tenant.
     *
     * @param period {@code 1h}, {@code 24h} (default), {@code 7d} or {@code 30d}
     * @return the overview
     */
    @Operation(summary = "Cluster overview (rows = tenants)")
    @GetMapping
    public OverviewResponse cluster(
            @RequestParam(defaultValue = "24h") @Pattern(regexp = OverviewPeriod.VALUES) String period) {
        return overview.cluster(OverviewPeriod.of(period));
    }

    /**
     * Overview of a tenant, one row per namespace.
     *
     * @param tenant tenant name
     * @param period {@code 1h}, {@code 24h} (default), {@code 7d} or {@code 30d}
     * @return the overview
     */
    @Operation(summary = "Tenant overview (rows = namespaces)")
    @GetMapping("/{tenant}")
    public OverviewResponse tenant(
            @PathVariable @PulsarName String tenant,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = OverviewPeriod.VALUES) String period) {
        return overview.tenant(tenant, OverviewPeriod.of(period));
    }

    /**
     * Overview of a namespace, one row per topic.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param period {@code 1h}, {@code 24h} (default), {@code 7d} or {@code 30d}
     * @return the overview
     */
    @Operation(summary = "Namespace overview (rows = topics)")
    @GetMapping("/{tenant}/{namespace}")
    public OverviewResponse namespace(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @RequestParam(defaultValue = "24h") @Pattern(regexp = OverviewPeriod.VALUES) String period) {
        return overview.namespace(tenant, namespace, OverviewPeriod.of(period));
    }
}
