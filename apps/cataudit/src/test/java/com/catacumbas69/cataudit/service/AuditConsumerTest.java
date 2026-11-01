package com.catacumbas69.cataudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.cataudit.TestProperties;
import com.catacumbas69.cataudit.model.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class AuditConsumerTest {

    private static final String EVENT = """
            {"specversion":"1.0","id":"e1","source":"/catacumbas69/cataponte",
             "type":"com.catacumbas69.cataponte.bridge.created.v1","subject":"b1","time":"2026-10-31T19:00:00Z",
             "datacontenttype":"application/json",
             "data":{"actor":"10.0.0.1","after":{"name":"fanout"}}}
            """;

    private final AuditStore store = mock(AuditStore.class);
    private final AuditConsumer consumer = new AuditConsumer(mock(PulsarClient.class), TestProperties.properties(),
            store, new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-10-31T20:00:00Z"), ZoneOffset.UTC), new CatacommonsScheduler(),
            new SimpleMeterRegistry());

    @Test
    void storesACloudEvent() {
        when(store.insert(any())).thenReturn(true);

        assertThat(consumer.handle(bytes(EVENT))).isEqualTo(AuditConsumer.Outcome.STORED);

        ArgumentCaptor<AuditEvent> stored = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).insert(stored.capture());
        assertThat(stored.getValue().type()).isEqualTo("com.catacumbas69.cataponte.bridge.created.v1");
        assertThat(stored.getValue().after()).containsEntry("name", "fanout");
    }

    @Test
    void acknowledgesADuplicate() {
        when(store.insert(any())).thenReturn(false);

        assertThat(consumer.handle(bytes(EVENT))).isEqualTo(AuditConsumer.Outcome.DUPLICATE);
    }

    @Test
    void dropsWhatIsNotACloudEvent() {
        assertThat(consumer.handle(bytes("not json"))).isEqualTo(AuditConsumer.Outcome.INVALID);
        assertThat(consumer.handle(bytes("{\"type\":\"x\"}"))).isEqualTo(AuditConsumer.Outcome.INVALID);
        verify(store, never()).insert(any());
    }

    @Test
    void asksForRedelivery_whenMongoIsDown() {
        when(store.insert(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(consumer.handle(bytes(EVENT))).isEqualTo(AuditConsumer.Outcome.RETRY);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
