package com.catacumbas69.catavigia.checks;

import com.catacumbas69.catavigia.model.AlertTypes;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code bridge.failed} and {@code bridge.blocked}: a bridge is {@code FAILED} or {@code BLOCKED}, from Cataponte's
 * status merged over its replicas ({@code GET /api/v1/bridges/status}).
 */
@Component
public class BridgesCheck implements Check {

    /** Check name. */
    public static final String NAME = "bridges";

    private static final Map<String, String> TYPE_BY_STATE = Map.of(
            "FAILED", AlertTypes.BRIDGE_FAILED,
            "BLOCKED", AlertTypes.BRIDGE_BLOCKED);
    private static final List<String> DETAILS = List.of("bridgeId", "state", "problem", "lastError",
            "stuckMessageId", "nextRetryAt", "sourceTopic", "activeReplicas", "replicas");

    private final AppsClient apps;
    private final AppUrls urls;

    /**
     * Creates the check.
     *
     * @param apps HTTP client
     * @param urls where Cataponte answers
     */
    public BridgesCheck(AppsClient apps, AppUrls urls) {
        this.apps = apps;
        this.urls = urls;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> types() {
        return Set.of(AlertTypes.BRIDGE_FAILED, AlertTypes.BRIDGE_BLOCKED);
    }

    @Override
    public List<Finding> run() throws SourceUnavailableException {
        JsonNode status = apps.get(urls.cataponte() + "/api/v1/bridges/status");
        List<Finding> findings = new ArrayList<>();
        for (JsonNode worker : status.path("workers")) {
            String type = TYPE_BY_STATE.get(worker.path("state").asText());
            if (type == null) {
                continue;
            }
            Map<String, String> details = new LinkedHashMap<>();
            for (String field : DETAILS) {
                JsonNode value = worker.get(field);
                if (value != null && !value.isNull()) {
                    details.put(field, value.asText());
                }
            }
            findings.add(new Finding(type, worker.path("name").asText(), details));
        }
        return findings;
    }
}
