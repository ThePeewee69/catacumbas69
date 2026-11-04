package com.catacumbas69.catametricas.controller;

import com.catacumbas69.catametricas.dto.ForcedCollectionResponse;
import com.catacumbas69.catametricas.service.ForcedCollection;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forced collection: reads a scope from Pulsar now instead of waiting for the next interval.
 */
@Tag(name = "Collection")
@RestController
@RequestMapping("/api/v1")
public class CollectController {

    private final ForcedCollection forced;

    /**
     * Creates the controller.
     *
     * @param forced runs forced collections
     */
    public CollectController(ForcedCollection forced) {
        this.forced = forced;
    }

    /**
     * Collects the cluster, a tenant or a namespace now and waits for it. A namespace already collected in the
     * current window (5 s) by another request is not collected again. Audited as {@code collection.forced}.
     *
     * @param tenant tenant, or none for the whole cluster
     * @param namespace namespace inside the tenant, or none for the whole tenant
     * @param request current request, for the audit actor
     * @return what was collected
     * @throws PulsarAdminException if the namespaces cannot be listed
     */
    @Operation(summary = "Collect a scope now (at most once per namespace every 5 s)")
    @PostMapping("/collect")
    public ForcedCollectionResponse collect(
            @RequestParam(required = false) @Pattern(regexp = MetricsController.NAME) String tenant,
            @RequestParam(required = false) @Pattern(regexp = MetricsController.NAME) String namespace,
            HttpServletRequest request) throws PulsarAdminException {
        return forced.collect(tenant, namespace, ClientAddress.of(request));
    }
}
