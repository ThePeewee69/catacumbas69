package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catametricas.TestProperties;
import com.catacumbas69.catametricas.dto.ForcedCollectionResponse;
import com.catacumbas69.catametricas.model.CollectTask;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ForcedCollectionTest {

    /** 7 s into a minute: the 5 s window started at :05. */
    private static final Instant NOW = Instant.parse("2026-11-04T09:00:07Z");
    private static final Instant WINDOW = Instant.parse("2026-11-04T09:00:05Z");

    private final StatsCollector collector = mock(StatsCollector.class);
    private final PulsarStatsReader reader = mock(PulsarStatsReader.class);
    private final CollectInterval interval = mock(CollectInterval.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final ForcedCollection forced = new ForcedCollection(collector, reader, interval, events,
            Clock.fixed(NOW, ZoneOffset.UTC), TestProperties.properties());

    @BeforeEach
    void interval() {
        when(interval.seconds()).thenReturn(60);
    }

    @Test
    void namespace_isCollectedAsAForcedTaskOfTheCurrentWindow_andAudited() throws Exception {
        when(collector.handle(any())).thenReturn(StatsCollector.Outcome.DONE);

        ForcedCollectionResponse response = forced.collect("home", "media", "10.0.0.1");

        verify(collector).handle(CollectTask.forced("home/media", WINDOW, 60));
        assertThat(CollectTask.forced("home/media", WINDOW, 60).id())
                .isEqualTo("forced:home/media@2026-11-04T09:00:05Z");
        assertThat(response.scope()).isEqualTo("home/media");
        assertThat(response.collected()).isEqualTo(1);
        assertThat(response.coalesced()).isZero();
        assertThat(response.failed()).isEmpty();
        verifyNoInteractions(reader);
        verify(events).publish(eq("collection"), eq("forced"), eq("home/media"),
                argThat((EventData data) -> "10.0.0.1".equals(data.actor())
                        && "1".equals(data.details().get("collected"))));
    }

    @Test
    void tenant_collectsItsNamespaces_countingCoalescedAndFailed() throws Exception {
        when(reader.namespaces()).thenReturn(List.of("home/a", "home/b", "home/c", "public/default"));
        when(collector.handle(argThat(task -> task != null && task.namespace().equals("home/a"))))
                .thenReturn(StatsCollector.Outcome.DONE);
        when(collector.handle(argThat(task -> task != null && task.namespace().equals("home/b"))))
                .thenReturn(StatsCollector.Outcome.DUPLICATE);
        when(collector.handle(argThat(task -> task != null && task.namespace().equals("home/c"))))
                .thenReturn(StatsCollector.Outcome.RETRY);

        ForcedCollectionResponse response = forced.collect("home", null, "10.0.0.1");

        assertThat(response.scope()).isEqualTo("home");
        assertThat(response.namespaces()).containsExactly("home/a", "home/b", "home/c");
        assertThat(response.collected()).isEqualTo(1);
        assertThat(response.coalesced()).isEqualTo(1);
        assertThat(response.failed()).containsExactly("home/c");
        verify(collector, never()).handle(argThat(task -> task != null && task.namespace().equals("public/default")));
    }

    @Test
    void cluster_collectsEveryNamespace() throws Exception {
        when(reader.namespaces()).thenReturn(List.of("home/a", "public/default"));
        when(collector.handle(any())).thenReturn(StatsCollector.Outcome.DONE);

        ForcedCollectionResponse response = forced.collect(null, null, "10.0.0.1");

        assertThat(response.scope()).isEqualTo("cluster");
        assertThat(response.collected()).isEqualTo(2);
    }

    @Test
    void namespaceWithoutTenant_isRefused() {
        assertThatThrownBy(() -> forced.collect(null, "media", "10.0.0.1"))
                .isInstanceOf(InvalidQueryException.class);
        verifyNoInteractions(collector, events);
    }
}
