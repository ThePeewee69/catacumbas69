package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverviewPeriodTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:07:42Z");

    @Test
    void lastHour_hasSixtyMinuteBuckets_endingWithTheCurrentMinute() {
        List<Instant> buckets = OverviewPeriod.LAST_HOUR.buckets(NOW);

        assertThat(buckets).hasSize(60);
        assertThat(buckets.get(59)).isEqualTo(Instant.parse("2026-10-31T10:07:00Z"));
        assertThat(buckets.get(0)).isEqualTo(Instant.parse("2026-10-31T09:08:00Z"));
    }

    @Test
    void lastMonth_usesSixHourBuckets_fromTheHourlyHistory() {
        List<Instant> buckets = OverviewPeriod.LAST_MONTH.buckets(NOW);

        assertThat(buckets).hasSize(120);
        assertThat(buckets.get(119)).isEqualTo(Instant.parse("2026-10-31T06:00:00Z"));
        assertThat(OverviewPeriod.LAST_MONTH.collection()).isEqualTo("contario_topic_hours");
    }

    @Test
    void parsesRequestValues() {
        assertThat(OverviewPeriod.of("24h")).isEqualTo(OverviewPeriod.LAST_DAY);
        assertThatThrownBy(() -> OverviewPeriod.of("2h")).isInstanceOf(IllegalArgumentException.class);
    }
}
