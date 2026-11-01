package com.catacumbas69.cataponte.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.catacumbas69.cataponte.TestBridges;
import com.catacumbas69.cataponte.TestProperties;
import com.catacumbas69.cataponte.dto.BridgeRuntimeStatus;
import com.catacumbas69.cataponte.dto.BridgeStatusResponse;
import com.catacumbas69.cataponte.model.WorkerStatusDocument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class BridgeStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T16:00:00Z");

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final BridgeManager manager = mock(BridgeManager.class);
    private final BridgeStatusService service = new BridgeStatusService(mongoTemplate, manager,
            new CataponteInstance("pod-a"), TestProperties.properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void mergesThisReplicaWithTheReportsOfTheOthers() {
        when(manager.getStatuses()).thenReturn(List.of(local("b1", BridgeState.RUNNING, 10, true)));
        when(mongoTemplate.find(any(Query.class), eq(WorkerStatusDocument.class))).thenReturn(List.of(
                report("b1", "pod-b", BridgeState.RUNNING, 5, false, null),
                report("b1", "pod-c", BridgeState.FAILED, 0, false, "Subscription type mismatch"),
                report("b2", "pod-b", BridgeState.BLOCKED, 0, false, "loop")));

        BridgeStatusResponse response = service.status();

        assertThat(response.activeCount()).isEqualTo(1);
        BridgeRuntimeStatus b1 = response.workers().get(0);
        assertThat(b1.state()).isEqualTo("RUNNING");
        assertThat(b1.messagesForwarded()).isEqualTo(15);
        assertThat(b1.replicas()).isEqualTo(3);
        assertThat(b1.activeReplicas()).isEqualTo(1);
        assertThat(b1.lastError()).isEqualTo("Subscription type mismatch");
        assertThat(response.workers().get(1).state()).isEqualTo("BLOCKED");
    }

    @Test
    void answersForThisReplica_whenMongoDbIsDown() {
        when(manager.getStatuses()).thenReturn(List.of(local("b1", BridgeState.RUNNING, 10, true)));
        when(mongoTemplate.find(any(Query.class), eq(WorkerStatusDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));

        BridgeStatusResponse response = service.status();

        assertThat(response.workers()).singleElement().satisfies(status -> {
            assertThat(status.replicas()).isEqualTo(1);
            assertThat(status.messagesForwarded()).isEqualTo(10);
        });
    }

    @Test
    void reportsTheStateOfTheMostAdvancedReplica() {
        List<BridgeRuntimeStatus> merged = BridgeStatusService.merge(List.of(
                report("b1", "pod-a", BridgeState.FAILED, 0, false, "boom"),
                report("b1", "pod-b", BridgeState.STARTING, 0, false, null)));

        assertThat(merged).singleElement().satisfies(status -> {
            assertThat(status.state()).isEqualTo("STARTING");
            assertThat(status.running()).isFalse();
            assertThat(status.lastError()).isEqualTo("boom");
        });
    }

    private static BridgeWorkerStatus local(String id, BridgeState state, long forwarded, boolean active) {
        return new BridgeWorkerStatus(id, "bridge-" + id, state, state == BridgeState.RUNNING, forwarded, 0, 0, NOW,
                TestBridges.SOURCE, List.of(TestBridges.DEST_A), true, null, null, active);
    }

    private static WorkerStatusDocument report(String id, String instance, BridgeState state, long forwarded,
                                               boolean active, String error) {
        return BridgeStatusService.toDocument(new BridgeWorkerStatus(id, "bridge-" + id, state,
                state == BridgeState.RUNNING, forwarded, 0, 0, NOW, TestBridges.SOURCE, List.of(TestBridges.DEST_A),
                true, error, null, active), instance, NOW);
    }
}
