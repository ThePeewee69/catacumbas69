package com.catacumbas69.cataponte.service;

import static com.catacumbas69.cataponte.TestBridges.DEST_A;
import static com.catacumbas69.cataponte.TestBridges.DEST_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.cataponte.TestBridges;
import com.catacumbas69.cataponte.model.BridgeDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditLogTest {

    private final EventPublisher events = mock(EventPublisher.class);
    private final AuditLog audit = new AuditLog(events);

    @Test
    void bridgeChanged_publishesEvent_withFlattenedDefinitions() {
        BridgeDocument before = TestBridges.unordered("1", DEST_A);
        BridgeDocument after = TestBridges.unordered("1", DEST_B);

        audit.bridgeChanged("10.0.0.1", AuditAction.UPDATE_BRIDGE, before, after);

        ArgumentCaptor<EventData> data = ArgumentCaptor.forClass(EventData.class);
        verify(events).publish(eq("bridge"), eq("updated"), eq("1"), data.capture());
        assertThat(data.getValue().actor()).isEqualTo("10.0.0.1");
        assertThat(data.getValue().before()).containsEntry("destinations", DEST_A)
                .containsEntry("source.topic", TestBridges.SOURCE).containsEntry("name", "bridge-1");
        assertThat(data.getValue().after()).containsEntry("destinations", DEST_B).containsEntry("enabled", "true")
                .containsEntry("ordered", "false").containsEntry("system", "false");
    }

    @Test
    void bridgeChanged_bySystem_publishesCataponteAsActor() {
        audit.bridgeChanged(AuditLog.SYSTEM_USER, AuditAction.UPDATE_BRIDGE, TestBridges.unordered("1", DEST_A),
                TestBridges.unordered("1", DEST_A));

        ArgumentCaptor<EventData> data = ArgumentCaptor.forClass(EventData.class);
        verify(events).publish(eq("bridge"), eq("updated"), eq("1"), data.capture());
        assertThat(data.getValue().actor()).isEqualTo("cataponte");
    }

    @Test
    void bridgeChanged_deleted_publishesOnlyBefore() {
        audit.bridgeChanged("10.0.0.1", AuditAction.DELETE_BRIDGE, TestBridges.unordered("1", DEST_A), null);

        ArgumentCaptor<EventData> data = ArgumentCaptor.forClass(EventData.class);
        verify(events).publish(eq("bridge"), eq("deleted"), eq("1"), data.capture());
        assertThat(data.getValue().before()).isNotEmpty();
        assertThat(data.getValue().after()).isNull();
    }

    @Test
    void bridgeChanged_neverThrows_whenPublishingFails() {
        doThrow(new IllegalStateException("boom")).when(events).publish(any(), any(), any(), any());

        assertThatCode(() -> audit.bridgeChanged("10.0.0.1", AuditAction.CREATE_BRIDGE, null,
                TestBridges.unordered("1", DEST_A))).doesNotThrowAnyException();
    }
}
