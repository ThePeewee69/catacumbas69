package com.catacumbas69.catasonarr.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueueServiceTest {

    private static QueueService.Item item(int id, String title, Instant done) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("title", title);
        r.put("size", 100L);
        r.put("sizeleft", 10L * id);
        r.put("estimatedCompletionTime", done);
        return new QueueService.Item(r, "H" + id, 0, true);
    }

    @Test
    void timeSpan_asDotNetWritesIt() {
        assertThat(QueueService.timeSpan(Duration.ofSeconds(750))).isEqualTo("00:12:30");
        assertThat(QueueService.timeSpan(Duration.ofSeconds(90_061))).isEqualTo("1.01:01:01");
        assertThat(QueueService.timeSpan(Duration.ofSeconds(-5))).isEqualTo("00:00:00");
    }

    @Test
    void hash_isStableAndPositive() {
        int a = QueueService.hash("trackedDownload-1-ABC-ep7");

        assertThat(a).isPositive().isEqualTo(QueueService.hash("trackedDownload-1-ABC-ep7"))
                .isNotEqualTo(QueueService.hash("trackedDownload-1-ABC-ep8"));
    }

    @Test
    void sort_byTimeLeftFirst_unknownLast_andByTitle() {
        List<QueueService.Item> items = List.of(item(1, "b", null), item(2, "a", Instant.ofEpochSecond(200)),
                item(3, "c", Instant.ofEpochSecond(100)));

        assertThat(QueueService.sort(items, "timeleft", true)).extracting(QueueService.Item::id)
                .containsExactly(3, 2, 1);
        assertThat(QueueService.sort(items, "title", false)).extracting(QueueService.Item::id)
                .containsExactly(3, 1, 2);
        assertThat(QueueService.sort(items, "progress", true)).extracting(QueueService.Item::id)
                .containsExactly(3, 2, 1);
    }
}
