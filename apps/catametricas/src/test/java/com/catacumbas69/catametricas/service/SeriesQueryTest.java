package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.catacumbas69.catametricas.model.Schema;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SeriesQueryTest {

    private static final Instant FROM = Instant.parse("2026-11-01T08:07:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T09:05:00Z");

    @Test
    void alignsBucketsToTheStep_andReadsMinutesBelowAnHour() {
        SeriesQuery query = SeriesQuery.of("home", null, "namespace", FROM, TO, "15m");

        assertThat(query.times()).first().isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
        assertThat(query.times()).last().isEqualTo(Instant.parse("2026-11-01T09:00:00Z"));
        assertThat(query.times()).hasSize(5);
        assertThat(query.step()).isEqualTo(Duration.ofMinutes(15));
        assertThat(query.collection()).isEqualTo(Schema.MINUTES);
        assertThat(query.end()).isEqualTo(Instant.parse("2026-11-01T09:15:00Z"));
        assertThat(query.groupBy().field()).isEqualTo(Schema.NAMESPACE);
    }

    @Test
    void readsHours_fromOneHour() {
        SeriesQuery query = SeriesQuery.of(null, null, "tenant", FROM, TO, "6h");

        assertThat(query.collection()).isEqualTo(Schema.HOURS);
        assertThat(query.timeField()).isEqualTo(Schema.HOUR);
        assertThat(query.times()).containsExactly(Instant.parse("2026-11-01T06:00:00Z"));
    }

    @Test
    void rejectsWhatDoesNotFit() {
        assertThatThrownBy(() -> SeriesQuery.of(null, "media", "tenant", FROM, TO, "1m"))
                .isInstanceOf(InvalidQueryException.class).hasMessageContaining("namespace needs tenant");
        assertThatThrownBy(() -> SeriesQuery.of(null, null, "namespace", FROM, TO, "1m"))
                .hasMessageContaining("needs tenant");
        assertThatThrownBy(() -> SeriesQuery.of("home", null, "topic", FROM, TO, "1m"))
                .hasMessageContaining("needs tenant and namespace");
        assertThatThrownBy(() -> SeriesQuery.of(null, null, "partition", FROM, TO, "1m"))
                .hasMessageContaining("groupBy");
        assertThatThrownBy(() -> SeriesQuery.of(null, null, "tenant", FROM, TO, "90m"))
                .hasMessageContaining("whole hours");
        assertThatThrownBy(() -> SeriesQuery.of(null, null, "tenant", FROM, TO, "10s"))
                .hasMessageContaining("step");
        assertThatThrownBy(() -> SeriesQuery.of(null, null, "tenant", TO, FROM, "1m"))
                .hasMessageContaining("from must be before to");
        assertThatThrownBy(() -> SeriesQuery.of(null, null, "tenant", FROM.minus(Duration.ofDays(30)), TO, "1m"))
                .hasMessageContaining("buckets");
    }
}
