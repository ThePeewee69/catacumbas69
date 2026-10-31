package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.catacumbas69.catametricas.model.CollectorStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

class StatsCollectorTest {

    private final CollectorLock lock = mock(CollectorLock.class);
    private final CollectInterval interval = mock(CollectInterval.class);
    private final CatametricasIndexes indexes = mock(CatametricasIndexes.class);
    private final PulsarStatsReader reader = mock(PulsarStatsReader.class);
    private final StatsWriter writer = mock(StatsWriter.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final StatsCollector collector = new StatsCollector(lock, interval, indexes, reader, writer, mongoTemplate,
            Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC), new SimpleMeterRegistry());

    @BeforeEach
    void interval() {
        when(interval.seconds()).thenReturn(60);
        when(lock.owner()).thenReturn("pod-1");
    }

    @Test
    void doesNothing_whenAnotherInstanceHoldsTheLease() {
        when(lock.tryAcquire(any())).thenReturn(false);

        collector.collectIfLeader();

        verifyNoInteractions(reader, writer);
        assertThat(collector.isLeader()).isFalse();
    }

    @Test
    void readsWritesAndRecordsStatus_whenLeader() throws Exception {
        when(lock.tryAcquire(Duration.ofSeconds(180))).thenReturn(true);
        PulsarReading reading = new PulsarReading(
                List.of(CollectionPlanTest.sample("persistent://home/media/orders", 1, 1)), Set.of("home/media"),
                Set.of());
        when(reader.read()).thenReturn(reading);
        when(writer.write(eq(reading), any())).thenReturn(new CollectionPlan(List.of(), List.of()));

        collector.collectIfLeader();

        verify(indexes).ensure();
        verify(mongoTemplate).save(any(CollectorStatus.class));
        assertThat(collector.lastStatus().topics()).isEqualTo(1);
        assertThat(collector.lastError()).isNull();
    }

    @Test
    void writesNothing_whenPulsarCannotBeRead() throws Exception {
        when(lock.tryAcquire(any())).thenReturn(true);
        PulsarAdminException down = mock(PulsarAdminException.class);
        when(down.getMessage()).thenReturn("Connection refused");
        when(reader.read()).thenThrow(down);

        collector.collectIfLeader();

        verify(writer, never()).write(any(), any());
        assertThat(collector.lastError()).isEqualTo("Connection refused");
    }

    @Test
    void skips_whenTheLeaseCannotBeChecked() {
        when(lock.tryAcquire(any())).thenThrow(new DataAccessResourceFailureException("mongo down"));

        collector.collectIfLeader();

        verifyNoInteractions(reader, writer);
        assertThat(collector.isLeader()).isFalse();
    }
}
