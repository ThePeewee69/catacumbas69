package com.catacumbas69.passario.service;

import static com.catacumbas69.passario.TestBridges.DEST_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.passario.TestBridges;
import com.catacumbas69.passario.TestProperties;
import com.catacumbas69.passario.model.BridgeDestination;
import com.catacumbas69.passario.model.BridgeDocument;
import com.catacumbas69.passario.repository.BridgeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class BridgeManagerTest {

    private final BridgeRepository repository = mock(BridgeRepository.class);
    private final BridgeWorkerFactory factory = mock(BridgeWorkerFactory.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final BridgeManager manager = new BridgeManager(repository, factory, TestProperties.properties(),
            clock, new SimpleMeterRegistry());

    @AfterEach
    void shutdown() {
        manager.shutdown();
    }

    @Test
    void startsWorker_forNewEnabledBridge() {
        BridgeDocument bridge = TestBridges.unordered("1", DEST_A);
        BridgeWorker worker = startingWorker(bridge, CompletableFuture.completedFuture(null));
        when(repository.findAll()).thenReturn(List.of(bridge));

        manager.reconcile();

        verify(worker).start();
        assertThat(manager.getStatuses()).hasSize(1);
    }

    @Test
    void doesNotStartDisabledBridge() {
        BridgeDocument bridge = TestBridges.unordered("1", DEST_A);
        bridge.setEnabled(false);
        when(repository.findAll()).thenReturn(List.of(bridge));

        manager.reconcile();

        verify(factory, never()).create(any());
    }

    @Test
    void stopsWorker_whenBridgeIsDisabled() {
        BridgeDocument bridge = TestBridges.unordered("1", DEST_A);
        BridgeWorker worker = startingWorker(bridge, CompletableFuture.completedFuture(null));
        when(repository.findAll()).thenReturn(List.of(bridge));
        manager.reconcile();

        bridge.setEnabled(false);
        manager.reconcile();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(worker).stop(any()));
    }

    @Test
    void stopsWorker_whenBridgeIsDeleted() {
        BridgeDocument bridge = TestBridges.unordered("1", DEST_A);
        BridgeWorker worker = startingWorker(bridge, CompletableFuture.completedFuture(null));
        when(repository.findAll()).thenReturn(List.of(bridge)).thenReturn(List.of());

        manager.reconcile();
        manager.reconcile();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(worker).stop(any()));
    }

    @Test
    void restartsWorker_whenVersionChanges() {
        BridgeDocument v1 = TestBridges.unordered("1", DEST_A);
        BridgeWorker first = startingWorker(v1, CompletableFuture.completedFuture(null));
        when(repository.findAll()).thenReturn(List.of(v1));
        manager.reconcile();

        BridgeDocument v2 = TestBridges.unordered("1", DEST_A);
        v2.setVersion(2L);
        BridgeWorker second = startingWorker(v2, CompletableFuture.completedFuture(null));
        when(repository.findAll()).thenReturn(List.of(v2));

        manager.reconcile();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(first).stop(any()));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            manager.reconcile();
            verify(second).start();
        });
    }

    @Test
    void keepsWorkersRunning_whenMongoCannotBeRead() {
        BridgeDocument bridge = TestBridges.unordered("1", DEST_A);
        BridgeWorker worker = startingWorker(bridge, CompletableFuture.completedFuture(null));
        when(repository.findAll())
                .thenReturn(List.of(bridge))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));

        manager.reconcile();
        manager.reconcile();

        verify(worker, never()).stop(any());
        assertThat(manager.getStatuses()).hasSize(1);
    }

    @Test
    void backsOff_afterStartFailure_andRetriesWhenDue() {
        BridgeDocument bridge = TestBridges.unordered("1", DEST_A);
        startingWorker(bridge, CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        when(repository.findAll()).thenReturn(List.of(bridge));

        manager.reconcile();
        manager.reconcile();
        verify(factory, times(1)).create(any());
        assertThat(manager.getStatuses())
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.state()).isEqualTo(BridgeState.FAILED);
                    assertThat(status.lastError()).isEqualTo("broker down");
                    assertThat(status.nextRetryAt()).isNotNull();
                });

        clock.advance(Duration.ofSeconds(1));
        manager.reconcile();
        verify(factory, times(2)).create(any());
    }

    @Test
    void isolatesBridges_whenOneFailsToBeCreated() {
        BridgeDocument broken = TestBridges.unordered("1", DEST_A);
        BridgeDocument healthy = TestBridges.unordered("2", DEST_A);
        when(factory.create(argThat(bridge -> bridge != null && "1".equals(bridge.getId()))))
                .thenThrow(new IllegalArgumentException("bad config"));
        BridgeWorker worker = startingWorker(healthy, CompletableFuture.completedFuture(null));
        when(repository.findAll()).thenReturn(List.of(broken, healthy));

        manager.reconcile();

        verify(worker).start();
    }

    @Test
    void blocksBridgesInALoop_writtenDirectlyToMongo() {
        BridgeDocument forward = TestBridges.unordered("1", DEST_A);
        BridgeDocument back = TestBridges.bridge("2", "Shared");
        back.getSource().setTopic(DEST_A);
        back.setDestinations(new java.util.ArrayList<>(List.of(new BridgeDestination(TestBridges.SOURCE))));
        when(repository.findAll()).thenReturn(List.of(forward, back));

        manager.reconcile();

        verify(factory, never()).create(any());
        assertThat(manager.getBlockedCount()).isEqualTo(2);
        assertThat(manager.getStatuses()).extracting(BridgeWorkerStatus::state)
                .containsOnly(BridgeState.BLOCKED);
    }

    @Test
    void stopsRunningWorker_whenAnotherBridgeClosesALoop() {
        BridgeDocument forward = TestBridges.unordered("1", DEST_A);
        BridgeWorker worker = startingWorker(forward, CompletableFuture.completedFuture(null));
        when(repository.findAll()).thenReturn(List.of(forward));
        manager.reconcile();
        BridgeDocument back = TestBridges.bridge("2", "Shared");
        back.getSource().setTopic(DEST_A);
        back.setDestinations(new java.util.ArrayList<>(List.of(new BridgeDestination(TestBridges.SOURCE))));
        when(repository.findAll()).thenReturn(List.of(forward, back));

        manager.reconcile();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(worker).stop(any()));
        assertThat(manager.getBlockedCount()).isEqualTo(2);
    }

    @Test
    void doesNotStartSecondWorker_whileFirstIsStarting() {
        BridgeDocument bridge = TestBridges.unordered("1", DEST_A);
        startingWorker(bridge, new CompletableFuture<>());
        when(repository.findAll()).thenReturn(List.of(bridge));

        manager.reconcile();
        manager.reconcile();

        verify(factory, times(1)).create(any());
    }

    private BridgeWorker startingWorker(BridgeDocument bridge, CompletableFuture<Void> startResult) {
        BridgeWorker worker = mock(BridgeWorker.class);
        when(worker.start()).thenReturn(startResult);
        when(worker.getConfigVersion()).thenReturn(bridge.getVersion());
        when(worker.getState()).thenReturn(BridgeState.RUNNING);
        when(worker.getStatus()).thenReturn(new BridgeWorkerStatus(bridge.getId(), bridge.getName(),
                BridgeState.RUNNING, true, 0, 0, 0, null, TestBridges.SOURCE, List.of(DEST_A), false, null, null));
        when(factory.create(argThat(candidate -> candidate == bridge))).thenReturn(worker);
        return worker;
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
