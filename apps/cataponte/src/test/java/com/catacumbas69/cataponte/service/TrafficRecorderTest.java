package com.catacumbas69.cataponte.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.catacumbas69.cataponte.model.TrafficRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

@SuppressWarnings("unchecked")
class TrafficRecorderTest {

    private static final String TOPIC = "persistent://home/media/in";

    private final TrafficWriter writer = mock(TrafficWriter.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:10Z"));
    private final TrafficRecorder recorder = new TrafficRecorder(writer, clock);

    @Test
    void flush_writesNothing_whenThereIsNoTraffic() {
        recorder.series("1", "b", TrafficRole.SOURCE, TOPIC);

        recorder.flush();

        verify(writer, never()).write(any());
    }

    @Test
    void flush_groupsCountsByMinute() {
        TopicTraffic series = recorder.series("1", "b", TrafficRole.SOURCE, TOPIC);
        series.add(10);
        series.add(5);
        clock.advance(Duration.ofMinutes(1));
        series.add(1);

        List<TopicTraffic.Drained> written = flushAndCapture();

        assertThat(written).extracting(TopicTraffic.Drained::messages).containsExactlyInAnyOrder(2L, 1L);
        assertThat(written).extracting(TopicTraffic.Drained::bytes).containsExactlyInAnyOrder(15L, 1L);
        assertThat(written).extracting(entry -> Instant.ofEpochMilli(entry.minuteStartMillis()))
                .containsExactlyInAnyOrder(
                        Instant.parse("2026-10-30T10:00:00Z"), Instant.parse("2026-10-30T10:01:00Z"));
    }

    @Test
    void flush_writesEachCountOnlyOnce() {
        recorder.series("1", "b", TrafficRole.SOURCE, TOPIC).add(3);

        recorder.flush();
        recorder.flush();

        verify(writer, times(1)).write(any());
    }

    @Test
    void flush_keepsCountsForTheNextAttempt_whenMongoFails() {
        TopicTraffic series = recorder.series("1", "b", TrafficRole.SOURCE, TOPIC);
        series.add(7);
        doThrow(new DataAccessResourceFailureException("down")).doNothing().when(writer).write(any());

        recorder.flush();
        series.add(3);
        recorder.flush();

        ArgumentCaptor<Collection<TopicTraffic.Drained>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(writer, times(2)).write(captor.capture());
        List<TopicTraffic.Drained> second = new ArrayList<>(captor.getAllValues().get(1));
        assertThat(second).extracting(TopicTraffic.Drained::messages).containsExactly(2L);
        assertThat(second).extracting(TopicTraffic.Drained::bytes).containsExactly(10L);
    }

    @Test
    void series_returnsTheSameCounter_forTheSameTopic() {
        TopicTraffic first = recorder.series("1", "old-name", TrafficRole.DESTINATION, TOPIC);
        TopicTraffic second = recorder.series("1", "new-name", TrafficRole.DESTINATION, TOPIC);

        assertThat(second).isSameAs(first);
        assertThat(second.bridgeName()).isEqualTo("new-name");
    }

    private List<TopicTraffic.Drained> flushAndCapture() {
        recorder.flush();
        ArgumentCaptor<Collection<TopicTraffic.Drained>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(writer).write(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    /** A clock tests can move forward. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
