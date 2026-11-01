package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catametricas.model.CollectTask;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.junit.jupiter.api.Test;

class CollectTaskPublisherTest {

    private final PulsarStatsReader reader = mock(PulsarStatsReader.class);
    @SuppressWarnings("unchecked")
    private final RetryingSender<CollectTask> sender = mock(RetryingSender.class);
    private final CollectInterval interval = mock(CollectInterval.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final CollectTaskPublisher publisher = new CollectTaskPublisher(reader, sender, interval,
            new CollectorState(events), Clock.fixed(Instant.parse("2026-11-01T09:01:41Z"), ZoneOffset.UTC));

    @Test
    void publishesOneTaskPerNamespace_keyedByNamespaceAndIntervalStart() throws Exception {
        when(interval.seconds()).thenReturn(60);
        when(reader.namespaces()).thenReturn(List.of("home/iot", "home/media"));

        publisher.publish();

        CollectTask media = new CollectTask("home/media@2026-11-01T09:01:00Z", "home/media",
                "2026-11-01T09:01:00Z", 60);
        verify(sender).send("home/media@2026-11-01T09:01:00Z", media);
        verify(sender).send(eq("home/iot@2026-11-01T09:01:00Z"), any(CollectTask.class));
    }

    @Test
    void publishesNothing_andReportsTheFailure_whenNamespacesCannotBeListed() throws Exception {
        when(interval.seconds()).thenReturn(60);
        when(reader.namespaces()).thenThrow(mock(PulsarAdminException.class));

        publisher.publish();

        verify(sender, never()).send(any(), any());
        verify(events).publish(eq("collection"), eq("failed"), eq("collector"), any(EventData.class));
    }

    @Test
    void intervalsAreAlignedToTheEpoch() {
        assertThat(CollectTaskPublisher.bucketOf(Instant.parse("2026-11-01T09:07:41Z"), 300))
                .isEqualTo(Instant.parse("2026-11-01T09:05:00Z"));
        assertThat(CollectionScheduler.next(Instant.parse("2026-11-01T09:07:41Z"), 300))
                .isEqualTo(Instant.parse("2026-11-01T09:10:01Z"));
    }
}
