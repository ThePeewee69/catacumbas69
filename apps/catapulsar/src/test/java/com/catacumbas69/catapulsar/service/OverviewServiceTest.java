package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catapulsar.dto.OverviewResponse;
import com.catacumbas69.catapulsar.service.CatametricasClient.Collector;
import com.catacumbas69.catapulsar.service.CatametricasClient.Current;
import com.catacumbas69.catapulsar.service.CatametricasClient.Series;
import com.catacumbas69.catapulsar.service.CatametricasClient.TopicStats;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:07:42Z");

    private final CatametricasClient catametricas = mock(CatametricasClient.class);
    private final OverviewService service = new OverviewService(catametricas, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void namespace_asksCatametricasForTopicsSeriesAndCollector() {
        when(catametricas.topics("home", "media")).thenReturn(List.of(new TopicStats(
                "persistent://home/media/orders", "home", "media", "orders", "orders",
                new Current(NOW, 3, 0, 0, 0, 0, 0, 0, 1, 1, 0), null)));
        when(catametricas.series("home", "media", "topic", Instant.parse("2026-11-01T09:08:00Z"), NOW, "1m"))
                .thenReturn(new Series("mongodb", "topic", 60, List.of(), List.of()));
        when(catametricas.collector()).thenReturn(new Collector(NOW.minusSeconds(30), 60));

        OverviewResponse overview = service.namespace("home", "media", OverviewPeriod.LAST_HOUR);

        assertThat(overview.scope()).isEqualTo("namespace");
        assertThat(overview.times()).hasSize(60);
        assertThat(overview.rows()).extracting(OverviewResponse.Row::name).containsExactly("orders");
        assertThat(overview.collectedAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(overview.intervalSeconds()).isEqualTo(60);
        verify(catametricas).series("home", "media", "topic", Instant.parse("2026-11-01T09:08:00Z"), NOW, "1m");
    }

    @Test
    void cluster_groupsByTenant() {
        when(catametricas.topics(null, null)).thenReturn(List.of());
        when(catametricas.series(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Series("mongodb", "tenant", 900, List.of(), List.of()));

        service.cluster(OverviewPeriod.LAST_DAY);

        verify(catametricas).series(null, null, "tenant", Instant.parse("2026-10-31T10:15:00Z"), NOW, "15m");
    }

    @Test
    void failsWhenCatametricasIsDown() {
        when(catametricas.topics(any(), any()))
                .thenThrow(new CatametricasUnavailableException(new IOException("refused")));

        assertThatThrownBy(() -> service.tenant("home", OverviewPeriod.LAST_WEEK))
                .isInstanceOf(CatametricasUnavailableException.class);
    }
}
