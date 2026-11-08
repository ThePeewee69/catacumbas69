package com.catacumbas69.catavigia.checks;

import com.catacumbas69.catacommons.alerts.AlertSetting;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.model.AlertTypes;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code collection.stale}: Catametricas has had no sample for {@code alerts.collection-stale-intervals}
 * collection intervals ({@code GET /api/v1/collector}).
 */
@Component
public class CollectionCheck implements Check {

    /** Check name. */
    public static final String NAME = "collection";
    /** Subject of the alert: collection is one for the whole platform. */
    public static final String SUBJECT = "catametricas";

    private final AppsClient apps;
    private final AppUrls urls;
    private final SharedSettings settings;
    private final Clock clock;

    /**
     * Creates the check.
     *
     * @param apps HTTP client
     * @param urls where Catametricas answers
     * @param settings the settings topic, for the number of intervals
     * @param clock time source
     */
    public CollectionCheck(AppsClient apps, AppUrls urls, ObjectProvider<SharedSettings> settings, Clock clock) {
        this.apps = apps;
        this.urls = urls;
        this.settings = settings.getIfAvailable();
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> types() {
        return Set.of(AlertTypes.COLLECTION_STALE);
    }

    @Override
    public List<Finding> run() throws SourceUnavailableException {
        JsonNode collector = apps.get(urls.catametricas() + "/api/v1/collector");
        long intervalSeconds = Math.max(1, collector.path("intervalSeconds").asLong(60));
        long intervals = AlertSetting.COLLECTION_STALE_INTERVALS.value(settings);
        Duration staleAfter = Duration.ofSeconds(intervalSeconds * intervals);
        Instant last = parse(collector.path("lastCollectionAt").asText(null));
        if (last != null && !last.plus(staleAfter).isBefore(clock.instant())) {
            return List.of();
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("lastCollectionAt", last == null ? "never" : last.toString());
        details.put("intervalSeconds", Long.toString(intervalSeconds));
        details.put("staleAfterSeconds", Long.toString(staleAfter.toSeconds()));
        return List.of(new Finding(AlertTypes.COLLECTION_STALE, SUBJECT, details));
    }

    private static Instant parse(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
