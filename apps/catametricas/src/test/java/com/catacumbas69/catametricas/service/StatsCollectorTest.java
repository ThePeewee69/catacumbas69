package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catametricas.model.CollectTask;
import com.catacumbas69.catametricas.model.TaskRun;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class StatsCollectorTest {

    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");
    private static final CollectTask TASK = CollectTask.of("home/media", Instant.parse("2026-11-01T09:00:00Z"), 60);

    private final CollectInterval interval = mock(CollectInterval.class);
    private final CatametricasIndexes indexes = mock(CatametricasIndexes.class);
    private final PulsarStatsReader reader = mock(PulsarStatsReader.class);
    private final StatsWriter writer = mock(StatsWriter.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final CollectorState state = new CollectorState(events);
    private final StatsCollector collector = new StatsCollector(interval, indexes, reader, writer, mongoTemplate,
            state, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

    @BeforeEach
    void interval() {
        when(interval.seconds()).thenReturn(60);
    }

    @Test
    void claimsReadsWritesAndRecords() throws Exception {
        PulsarReading reading = new PulsarReading("home/media", List.of(), Set.of("persistent://home/media/x"));
        when(reader.read("home/media")).thenReturn(reading);
        when(writer.write(reading, NOW)).thenReturn(new StatsWriter.Result(0, 0, 0, false));

        assertThat(collector.handle(TASK)).isEqualTo(StatsCollector.Outcome.DONE);

        verify(indexes).ensure();
        verify(mongoTemplate).insert(argThat((TaskRun run) -> run.id().equals("home/media@2026-11-01T09:00:00Z")
                && run.instance().equals(state.instance()) && run.finishedAt() == null));
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(TaskRun.class));
        assertThat(state.lastRun().failedTopics()).isEqualTo(1);
        assertThat(state.lastError()).isNull();
    }

    @Test
    void skipsATaskAnotherInstanceClaimed() {
        when(mongoTemplate.insert(any(TaskRun.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThat(collector.handle(TASK)).isEqualTo(StatsCollector.Outcome.DUPLICATE);

        verifyNoInteractions(reader, writer, events);
    }

    @Test
    void retries_whenTheClaimCannotBeStored() {
        when(mongoTemplate.insert(any(TaskRun.class))).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(collector.handle(TASK)).isEqualTo(StatsCollector.Outcome.RETRY);

        verifyNoInteractions(reader, writer);
        assertThat(state.lastError()).startsWith("claim: MongoDB");
    }

    @Test
    void releasesTheClaimAndRetries_whenPulsarFails() throws Exception {
        when(reader.read("home/media")).thenThrow(mock(PulsarAdminException.class));

        assertThat(collector.handle(TASK)).isEqualTo(StatsCollector.Outcome.RETRY);

        verify(mongoTemplate).remove(any(Query.class), eq(TaskRun.class));
        verifyNoInteractions(writer);
    }

    @Test
    void marksTheTopicsAsGone_whenTheNamespaceNoLongerExists() throws Exception {
        when(reader.read("home/media")).thenThrow(mock(PulsarAdminException.NotFoundException.class));
        when(writer.write(any(), eq(NOW))).thenReturn(new StatsWriter.Result(0, 0, 3, false));

        assertThat(collector.handle(TASK)).isEqualTo(StatsCollector.Outcome.DONE);

        verify(writer).write(argThat(reading -> reading.samples().isEmpty()
                && reading.namespace().equals("home/media")), eq(NOW));
    }

    @Test
    void dropsInvalidTasks() {
        assertThat(collector.handle(null)).isEqualTo(StatsCollector.Outcome.INVALID);
        assertThat(collector.handle(new CollectTask("x", "no-tenant", null, 60)))
                .isEqualTo(StatsCollector.Outcome.INVALID);

        verifyNoInteractions(mongoTemplate, reader, writer);
    }

    @Test
    void publishesFailedOnce_andRecoveredAfterwards() throws Exception {
        PulsarReading reading = new PulsarReading("home/media", List.of(), Set.of());
        when(reader.read("home/media")).thenThrow(mock(PulsarAdminException.class))
                .thenThrow(mock(PulsarAdminException.class))
                .thenReturn(reading);
        when(writer.write(reading, NOW)).thenReturn(new StatsWriter.Result(0, 0, 0, false));

        collector.handle(TASK);
        collector.handle(TASK);
        collector.handle(TASK);

        verify(events, times(1)).publish(eq("collection"), eq("failed"), eq("collector"), any(EventData.class));
        verify(events, times(1)).publish(eq("collection"), eq("recovered"), eq("collector"), any(EventData.class));
    }

    @Test
    void publishesNothing_whileHealthy() throws Exception {
        PulsarReading reading = new PulsarReading("home/media", List.of(), Set.of());
        when(reader.read("home/media")).thenReturn(reading);
        when(writer.write(reading, NOW)).thenReturn(new StatsWriter.Result(0, 0, 0, false));

        collector.handle(TASK);
        collector.handle(TASK);

        verify(events, never()).publish(any(), any(), any(), any());
    }

    @Test
    void retries_whenMongoDbFailsWhileWriting() throws Exception {
        PulsarReading reading = new PulsarReading("home/media", List.of(), Set.of());
        when(reader.read("home/media")).thenReturn(reading);
        doThrow(new DataAccessResourceFailureException("down")).when(writer).write(reading, NOW);

        assertThat(collector.handle(TASK)).isEqualTo(StatsCollector.Outcome.RETRY);

        verify(mongoTemplate).remove(any(Query.class), eq(TaskRun.class));
    }
}
