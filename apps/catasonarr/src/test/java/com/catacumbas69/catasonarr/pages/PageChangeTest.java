package com.catacumbas69.catasonarr.pages;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.events.EventData;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageChangeTest {

    @Test
    void of_entityAndActionFromTheType_andTheSeriesFromItsData() {
        PageChange change = PageChange.of(event("series.added", "Andor (12)",
                new EventData("api-key@10.0.0.5", null, Map.of("id", "12"), null))).orElseThrow();

        assertThat(change).isEqualTo(new PageChange("series", "added", "Andor (12)", "api-key@10.0.0.5", 12,
                "2026-11-24T22:00:00Z"));
    }

    @Test
    void of_theSeriesFromTheSubject_whenTheDataHasNoId() {
        assertThat(PageChange.of(event("series.refreshed", "Dark (7)", new EventData("catasonarr", null, null, null)))
                .orElseThrow().seriesId()).isEqualTo(7);
    }

    @Test
    void of_noSeriesForOtherEntities_andNothingForAnotherType() {
        assertThat(PageChange.of(event("episode.monitored", "episodes [1, 2]", null)).orElseThrow().seriesId())
                .isNull();
        assertThat(PageChange.of(new CloudEvent("1.0", "id", "x", "not.an.event", null, null, null, null)))
                .isEmpty();
    }

    private static CloudEvent event(String type, String subject, EventData data) {
        return new CloudEvent("1.0", "id", "catasonarr", "com.catacumbas69.catasonarr." + type + ".v1", subject,
                "2026-11-24T22:00:00Z", "application/json", data);
    }
}
