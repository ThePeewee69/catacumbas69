package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catametricas.dto.SeriesResponse.Group;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeriesAssemblerTest {

    private static final Instant T0 = Instant.parse("2026-11-01T08:00:00Z");
    private static final List<Instant> TIMES = List.of(T0, T0.plusSeconds(900), T0.plusSeconds(1800));

    @Test
    void addsTrafficPerBucket_andKeepsTheLastBacklog() {
        SeriesAssembler assembler = new SeriesAssembler(TIMES, Duration.ofMinutes(15))
                .add("media", T0.plusSeconds(60), 10, 5, 100, 50, 7)
                .add("media", T0.plusSeconds(120), 20, 5, 200, 50, 3)
                .add("media", T0.plusSeconds(1800), 1, 1, 10, 10, 9)
                .add("iot", T0, 4, 0, 40, 0, 0)
                .add("media", T0.minusSeconds(60), 99, 99, 99, 99, 99)
                .add("media", T0.plusSeconds(2700), 99, 99, 99, 99, 99);

        List<Group> groups = assembler.groups();

        assertThat(groups).extracting(Group::key).containsExactly("iot", "media");
        Group media = groups.get(1);
        assertThat(media.msgsIn()).containsExactly(30L, 0L, 1L);
        assertThat(media.bytesIn()).containsExactly(300L, 0L, 10L);
        assertThat(media.backlog()).containsExactly(3L, null, 9L);
    }
}
