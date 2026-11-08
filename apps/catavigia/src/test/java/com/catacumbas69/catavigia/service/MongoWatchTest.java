package com.catacumbas69.catavigia.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.consumers.InstanceId;
import com.catacumbas69.catacommons.consumers.ProbeResult;
import com.catacumbas69.catavigia.model.Alert;
import com.catacumbas69.catavigia.model.ArchivedAlert;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;

class MongoWatchTest {

    private final MongoConnectionProbe probe = mock(MongoConnectionProbe.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final AlertEvents events = mock(AlertEvents.class);
    private final AlertMetrics metrics = mock(AlertMetrics.class);
    private MongoWatch watch;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        InstanceId instance = mock(InstanceId.class);
        when(instance.id()).thenReturn("pod-1");
        watch = new MongoWatch(probe, mongoTemplate, events, metrics, mock(ObjectProvider.class), instance,
                Clock.fixed(Instant.parse("2026-11-07T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void opensInMemory_afterTheDebounceRounds_andArchivesWhenMongoDbAnswersAgain() {
        when(probe.probe()).thenReturn(ProbeResult.down(5000, "timeout"));
        watch.tick();
        assertThat(watch.alert()).isEmpty();
        watch.tick();
        assertThat(watch.alert()).hasValueSatisfying(alert -> {
            assertThat(alert.type()).isEqualTo("mongodb.down");
            assertThat(alert.subject()).isEqualTo("catavigia@pod-1");
            assertThat(alert.severity()).isEqualTo("critical");
        });
        verify(events).opened(any(Alert.class));

        when(probe.probe()).thenReturn(ProbeResult.up(3));
        watch.tick();
        assertThat(watch.alert()).isPresent();
        watch.tick();

        assertThat(watch.alert()).isEmpty();
        ArgumentCaptor<ArchivedAlert> archived = ArgumentCaptor.forClass(ArchivedAlert.class);
        verify(mongoTemplate).save(archived.capture());
        assertThat(archived.getValue().type()).isEqualTo("mongodb.down");
        verify(events).closed(archived.getValue());
    }

    @Test
    void keepsTheAlert_untilItCanBeArchived() {
        when(probe.probe()).thenReturn(ProbeResult.down(5000, "timeout"));
        watch.tick();
        watch.tick();
        when(probe.probe()).thenReturn(ProbeResult.up(3));
        when(mongoTemplate.save(any(ArchivedAlert.class))).thenThrow(new IllegalStateException("still flaky"));
        watch.tick();
        watch.tick();

        assertThat(watch.alert()).isPresent();
        verify(events, never()).closed(any());
    }
}
