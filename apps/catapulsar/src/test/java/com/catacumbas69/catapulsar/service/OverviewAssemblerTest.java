package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catapulsar.dto.OverviewResponse.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class OverviewAssemblerTest {

    private static final Instant T0 = Instant.parse("2026-10-31T10:00:00Z");
    private static final List<Instant> BUCKETS = List.of(T0, T0.plusSeconds(60), T0.plusSeconds(120));

    @Test
    void namespaceView_sumsPartitionsIntoTheirTopic() {
        OverviewAssembler assembler = new OverviewAssembler("baseName", BUCKETS, Duration.ofMinutes(1))
                .addSnapshot(snapshot("media", "orders", 10, 2.5))
                .addSnapshot(snapshot("media", "orders", 5, 1.5))
                .addSnapshot(snapshot("media", "audit", 0, 0));

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
        OverviewAssembler assembler = new OverviewAssembler("namespace", BUCKETS, Duration.ofMinutes(1))
                .addSnapshot(snapshot("media", "orders", 1, 0))
                .addSnapshot(snapshot("media", "orders", 1, 0))
                .addSnapshot(snapshot("media", "audit", 1, 0))
                .addSnapshot(snapshot("logs", "app", 1, 0));

        assertThat(assembler.rows()).extracting(row -> row.name() + "=" + row.current().topics())
                .containsExactly("logs=1", "media=2");
        assertThat(assembler.summary().current().topics()).isEqualTo(3);
    }

    @Test
    void history_fillsBuckets_periodTotals_andKeepsTheLatestBacklog() {
        OverviewAssembler assembler = new OverviewAssembler("namespace", BUCKETS, Duration.ofMinutes(1))
                .addHistory(history("media", T0, 10, 8, 100), "minute")
                .addHistory(history("media", T0.plusSeconds(120), 5, 7, 98), "minute")
                .addHistory(history("media", T0.minusSeconds(60), 999, 999, 1), "minute");   // before the period

        Row media = assembler.rows().get(0);

        assertThat(media.series().msgsIn()).containsExactly(10L, 0L, 5L);
        assertThat(media.series().msgsOut()).containsExactly(8L, 0L, 7L);
        assertThat(media.series().backlog()).containsExactly(100L, null, 98L);
        assertThat(media.period().msgsIn()).isEqualTo(15);
        assertThat(media.current()).isNull();   // only history: nothing exists now
    }

    @Test
    void summary_addsRowsTogether_includingAllTimeTotals() {
        OverviewAssembler assembler = new OverviewAssembler("namespace", BUCKETS, Duration.ofMinutes(1))
                .addHistory(history("media", T0, 10, 8, 100), "minute")
                .addHistory(history("logs", T0, 1, 1, 3), "minute")
                .addTotal(new Document("namespace", "media").append("msgsIn", 1_000L).append("msgsOut", 900L))
                .addTotal(new Document("namespace", "logs").append("msgsIn", 50).append("msgsOut", 40));

        Row summary = assembler.summary();

        assertThat(summary.name()).isNull();
        assertThat(summary.series().msgsIn()).containsExactly(11L, 0L, 0L);
        assertThat(summary.series().backlog()).containsExactly(103L, null, null);
        assertThat(summary.total().msgsIn()).isEqualTo(1_050);
        assertThat(summary.total().msgsOut()).isEqualTo(940);
    }

    private static Document snapshot(String namespace, String baseName, long backlog, double rateIn) {
        return new Document("tenant", "home").append("namespace", namespace).append("baseName", baseName)
                .append("backlogMsgs", backlog).append("msgRateIn", rateIn).append("consumers", 1);
    }

    private static Document history(String namespace, Instant minute, long in, long out, long backlog) {
        return new Document("_id", new Document("namespace", namespace).append("minute", Date.from(minute)))
                .append("msgsIn", in).append("msgsOut", out).append("backlogMsgs", backlog);
    }
}
