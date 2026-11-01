package com.catacumbas69.cataponte.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.cataponte.TestBridges;
import com.catacumbas69.cataponte.model.WorkerStatusDocument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class WorkerStatusReporterTest {

    private static final Instant NOW = Instant.parse("2026-11-01T16:00:00Z");

    private final BridgeManager manager = mock(BridgeManager.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final BulkOperations bulk = mock(BulkOperations.class, RETURNS_SELF);
    private final WorkerStatusReporter reporter = new WorkerStatusReporter(manager, mongoTemplate,
            new CataponteInstance("pod-a"), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void writesOneReportPerBridge_andRemovesReportsOfBridgesNoLongerRunHere() {
        when(manager.getStatuses()).thenReturn(List.of(new BridgeWorkerStatus("b1", "bridge-b1", BridgeState.RUNNING,
                true, 3, 0, 0, NOW, TestBridges.SOURCE, List.of(TestBridges.DEST_A), true, null, null, true)));
        when(mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, WorkerStatusDocument.class)).thenReturn(bulk);

        reporter.report();

        ArgumentCaptor<WorkerStatusDocument> report = ArgumentCaptor.forClass(WorkerStatusDocument.class);
        verify(bulk).replaceOne(any(Query.class), report.capture(), any(FindAndReplaceOptions.class));
        assertThat(report.getValue().id()).isEqualTo("b1@pod-a");
        assertThat(report.getValue().active()).isTrue();
        assertThat(report.getValue().updatedAt()).isEqualTo(NOW);
        verify(bulk).execute();
        ArgumentCaptor<Query> removed = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(removed.capture(), eq(WorkerStatusDocument.class));
        assertThat(removed.getValue().getQueryObject()).containsEntry("instance", "pod-a");
        assertThat(removed.getValue().getQueryObject().get("bridgeId", Document.class).get("$nin"))
                .isEqualTo(List.of("b1"));
    }
}
