package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConsumerStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ConsumerStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T22:00:00Z");

    private final AuditService audit = mock(AuditService.class);
    private final PulsarAdmin admin = mock(PulsarAdmin.class, RETURNS_DEEP_STUBS);
    private final ConsumerStatusService service = new ConsumerStatusService(mock(PulsarClient.class),
            admin, mock(CatacommonsScheduler.class, RETURNS_DEEP_STUBS), audit, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void cataudit_isFound() throws Exception {
        when(admin.namespaces().getNamespaces("catacumbas69")).thenReturn(List.of("catacumbas69/cataudit"));
        when(admin.topics().getList("catacumbas69/cataudit"))
                .thenReturn(List.of("persistent://catacumbas69/cataudit/consumers"));
        service.discover();
    }

    @Test
    void aReplicaThatStoppedReporting_isStale() {
        assertThat(ConsumerStatusService.isStale(status(NOW.minusSeconds(5)), NOW)).isFalse();
        assertThat(ConsumerStatusService.isStale(status(NOW.minusSeconds(60)), NOW)).isTrue();
        assertThat(ConsumerStatusService.isStale(status(null), NOW)).isTrue();
    }

    @Test
    void anAppIsKnown_onlyOnceFound() throws Exception {
        assertThatThrownBy(() -> service.send("catasonarr", "*", "*", "pause", "10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Unknown app");
        when(admin.namespaces().getNamespaces("catacumbas69"))
                .thenReturn(List.of("catacumbas69/cataudit", "catacumbas69/catasonarr"));
        when(admin.topics().getList("catacumbas69/catasonarr"))
                .thenReturn(List.of("persistent://catacumbas69/catasonarr/consumers"));
        service.discover();
        assertThatThrownBy(() -> service.send("catasonarr", "*", "pages", "pause", "10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Command not sent");
    }

    @Test
    void refusesAnUnknownAppOrAction() {
        assertThatThrownBy(() -> service.send("cataponte", "*", "*", "pause", "10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Unknown app");
        assertThatThrownBy(() -> service.send("cataudit", "*", "*", "explode", "10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Unknown action");
        verify(audit, never()).record(anyString(), any(), anyString(), anyMap());
    }

    @Test
    void answers503_whenTheCommandCannotBeSent() {
        assertThatThrownBy(() -> service.send("cataudit", "*", "audit", "pause", "10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Command not sent");
        verify(audit, never()).record(eq("10.0.0.1"), any(), anyString(), anyMap());
    }

    @Test
    void listsNothing_beforeAnyTopicWasRead() {
        assertThat(service.list()).isEmpty();
    }

    private static ConsumerStatus status(Instant updatedAt) {
        return new ConsumerStatus("cataudit", "pod-1", "audit", "t", "s", "RUNNING", null, "cataudit",
                NOW.toString(), updatedAt == null ? null : updatedAt.toString(), null, 10, null);
    }
}
