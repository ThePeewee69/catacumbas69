package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catapulsar.dto.OverviewResponse.Row;
import com.catacumbas69.catapulsar.service.CatametricasClient.Current;
import com.catacumbas69.catapulsar.service.CatametricasClient.Group;
import com.catacumbas69.catapulsar.service.CatametricasClient.TopicStats;
import com.catacumbas69.catapulsar.service.CatametricasClient.Traffic;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverviewAssemblerTest {

    private static final Instant T0 = Instant.parse("2026-10-31T10:00:00Z");
    private static final List<Instant> BUCKETS = List.of(T0, T0.plusSeconds(60), T0.plusSeconds(120));

    @Test
    void namespaceView_sumsPartitionsIntoTheirTopic() {
        OverviewAssembler assembler = new OverviewAssembler(OverviewScope.NAMESPACE, BUCKETS)
                .addTopic(topic("media", "orders", 10, 2.5))
                .addTopic(topic("media", "orders", 5, 1.5))
                .addTopic(topic("media", "audit", 0, 0));

        List<Row> rows = assembler.rows();

        assertThat(rows).extracting(Row::name).containsExactly("audit", "orders");
        Row orders = rows.get(1);
        assertThat(orders.current().topics()).isEqualTo(1);
        assertThat(orders.current().partitions()).isEqualTo(2);
        assertThat(orders.current().backlogMsgs()).isEqualTo(15);
        assertThat(orders.current().msgRateIn()).isEqualTo(4.0);
    }

    @Test
    void tenantView_countsDistinctTopicsPerNamespace() {
        OverviewAssembler assembler = new OverviewAssembler(OverviewScope.TENANT, BUCKETS)
                .addTopic(topic("media", "orders", 1, 0))
                .addTopic(topic("media", "orders", 1, 0))
                .addTopic(topic("media", "audit", 1, 0))
                .addTopic(topic("logs", "app", 1, 0));

        assertThat(assembler.rows()).extracting(row -> row.name() + "=" + row.current().topics())
                .containsExactly("logs=1", "media=2");
        assertThat(assembler.summary().current().topics()).isEqualTo(3);
    }

    @Test
    void goneTopics_keepTheirTotals_withoutCurrentState() {
        OverviewAssembler assembler = new OverviewAssembler(OverviewScope.TENANT, BUCKETS)
                .addTopic(new TopicStats("persistent://home/media/old", "home", "media", "old", "old", null,
                        new Traffic(7, 6, 70, 60)));

        Row media = assembler.rows().get(0);

        assertThat(media.current()).isNull();
        assertThat(media.total().msgsIn()).isEqualTo(7);
    }

    @Test
    void series_fillTheBucketsOnTheChart_andThePeriodTotals() {
        List<Instant> times = List.of(T0.minusSeconds(60), T0, T0.plusSeconds(60), T0.plusSeconds(120));
        OverviewAssembler assembler = new OverviewAssembler(OverviewScope.TENANT, BUCKETS)
                .addSeries(times, new Group("media", List.of(999L, 10L, 0L, 5L), List.of(999L, 8L, 0L, 7L),
                        List.of(1L, 100L, 0L, 50L), List.of(1L, 80L, 0L, 70L), Arrays.asList(1L, 100L, null, 98L)));

        Row media = assembler.rows().get(0);

        assertThat(media.series().msgsIn()).containsExactly(10L, 0L, 5L);
        assertThat(media.series().msgsOut()).containsExactly(8L, 0L, 7L);
        assertThat(media.series().backlog()).containsExactly(100L, null, 98L);
        assertThat(media.period().msgsIn()).isEqualTo(15);
        assertThat(media.period().bytesIn()).isEqualTo(150);
        assertThat(media.current()).isNull();
    }

    @Test
    void summary_addsRowsTogether_includingAllTimeTotals() {
        OverviewAssembler assembler = new OverviewAssembler(OverviewScope.TENANT, BUCKETS)
                .addSeries(BUCKETS, group("media", 10, 100))
                .addSeries(BUCKETS, group("logs", 1, 3))
                .addTopic(new TopicStats("persistent://home/media/a", "home", "media", "a", "a", null,
                        new Traffic(1_000, 900, 0, 0)))
                .addTopic(new TopicStats("persistent://home/logs/b", "home", "logs", "b", "b", null,
                        new Traffic(50, 40, 0, 0)));

        Row summary = assembler.summary();

        assertThat(summary.name()).isNull();
        assertThat(summary.series().msgsIn()).containsExactly(11L, 0L, 0L);
        assertThat(summary.series().backlog()).containsExactly(103L, null, null);
        assertThat(summary.total().msgsIn()).isEqualTo(1_050);
        assertThat(summary.total().msgsOut()).isEqualTo(940);
    }

    private static Group group(String key, long in, long backlog) {
        return new Group(key, List.of(in, 0L, 0L), List.of(0L, 0L, 0L), List.of(0L, 0L, 0L), List.of(0L, 0L, 0L),
                Arrays.asList(backlog, null, null));
    }

    private static TopicStats topic(String namespace, String baseName, long backlog, double rateIn) {
        return new TopicStats("persistent://home/" + namespace + "/" + baseName, "home", namespace, baseName,
                baseName, new Current(T0, backlog, 0, rateIn, 0, 0, 0, 0, 0, 0, 1), null);
    }
}
