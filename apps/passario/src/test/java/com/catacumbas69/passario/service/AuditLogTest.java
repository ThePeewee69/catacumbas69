package com.catacumbas69.passario.service;

import static com.catacumbas69.passario.TestBridges.DEST_A;
import static com.catacumbas69.passario.TestBridges.DEST_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.passario.TestBridges;
import com.catacumbas69.passario.model.AuditRecord;
import com.catacumbas69.passario.model.BridgeDocument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

@SuppressWarnings("unchecked")
class AuditLogTest {

    private static final Instant NOW = Instant.parse("2026-10-30T22:30:00Z");

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final AuditLog audit = new AuditLog(mongoTemplate, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void bridgeChanged_storesWhoWhatAndBothDefinitions() {
        BridgeDocument before = TestBridges.unordered("1", DEST_A);
        BridgeDocument after = TestBridges.unordered("1", DEST_B);

        audit.bridgeChanged("10.0.0.1", AuditAction.UPDATE_BRIDGE, before, after);

        ArgumentCaptor<AuditRecord> stored = ArgumentCaptor.forClass(AuditRecord.class);
        verify(mongoTemplate).insert(stored.capture());
        AuditRecord entry = stored.getValue();
        assertThat(entry.getTimestamp()).isEqualTo(NOW);
        assertThat(entry.getUser()).isEqualTo("10.0.0.1");
        assertThat(entry.getAction()).isEqualTo("UPDATE_BRIDGE");
        assertThat(entry.getApplication()).isEqualTo("passario");
        assertThat(entry.getTopic()).isEqualTo(TestBridges.SOURCE);
        assertThat(entry.getTopicProperties()).containsEntry("bridgeId", "1").containsEntry("name", "bridge-1");
        assertThat((Map<String, Object>) entry.getTopicProperties().get("before"))
                .containsEntry("destinations", List.of(DEST_A));
        assertThat((Map<String, Object>) entry.getTopicProperties().get("after"))
                .containsEntry("destinations", List.of(DEST_B))
                .containsEntry("enabled", true)
                .containsEntry("ordered", false);
    }

    @Test
    void bridgeChanged_leavesOutTheMissingSide() {
        audit.bridgeChanged("10.0.0.1", AuditAction.DELETE_BRIDGE, TestBridges.unordered("1", DEST_A), null);

        ArgumentCaptor<AuditRecord> stored = ArgumentCaptor.forClass(AuditRecord.class);
        verify(mongoTemplate).insert(stored.capture());
        assertThat(stored.getValue().getTopicProperties()).containsKey("before").doesNotContainKey("after");
    }

    @Test
    void bridgeChanged_neverThrows_whenMongoFails() {
        when(mongoTemplate.insert(any(AuditRecord.class))).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatCode(() -> audit.bridgeChanged("10.0.0.1", AuditAction.CREATE_BRIDGE, null,
                TestBridges.unordered("1", DEST_A))).doesNotThrowAnyException();
    }
}
