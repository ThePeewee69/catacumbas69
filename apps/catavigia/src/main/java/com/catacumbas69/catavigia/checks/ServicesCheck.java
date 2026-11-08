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
 * {@code service.down}: an app's readiness ({@code /actuator/health/readiness}) is not {@code UP} or does not
 * answer (orch.md decision 79). The components of its full health that are not {@code UP} go in the details, as
 * information: an app whose dependency is down is not down itself. This check never fails: an app that does not
 * answer is what it reports.
 */
@Component
public class ServicesCheck implements Check {

    /** Check name. */
    public static final String NAME = "services";

    private final AppsClient apps;
    private final AppUrls urls;

    /**
     * Creates the check.
     *
     * @param apps HTTP client
     * @param urls where the apps answer
     */
    public ServicesCheck(AppsClient apps, AppUrls urls) {
        this.apps = apps;
        this.urls = urls;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> types() {
        return Set.of(AlertTypes.SERVICE_DOWN);
    }

    @Override
    public List<Finding> run() {
        List<Finding> findings = new ArrayList<>();
        urls.all().forEach((app, url) -> {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("url", url);
            try {
                String status = apps.getAnyStatus(url + "/actuator/health/readiness").path("status").asText("?");
                if ("UP".equals(status)) {
                    return;
                }
                details.put("readiness", status);
                details.put("components", componentsNotUp(url));
            } catch (SourceUnavailableException e) {
                details.put("error", e.getMessage());
            }
            findings.add(new Finding(AlertTypes.SERVICE_DOWN, app, details));
        });
        return findings;
    }

    /** The components of the full health that are not UP, e.g. {@code mongo=DOWN}; best effort. */
    private String componentsNotUp(String url) {
        try {
            JsonNode components = apps.getAnyStatus(url + "/actuator/health").path("components");
            List<String> down = new ArrayList<>();
            components.properties().forEach(entry -> {
                String status = entry.getValue().path("status").asText("?");
                if (!"UP".equals(status)) {
                    down.add(entry.getKey() + "=" + status);
                }
            });
            return down.isEmpty() ? "none" : String.join(", ", down);
        } catch (SourceUnavailableException e) {
            return "unknown (" + e.getMessage() + ")";
        }
    }
}
