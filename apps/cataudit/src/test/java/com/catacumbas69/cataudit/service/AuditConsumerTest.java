package com.catacumbas69.cataudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConsumerControl;
import com.catacumbas69.catacommons.consumers.ConsumerGate;
import com.catacumbas69.catacommons.consumers.DeadLetterWriter;
import com.catacumbas69.catacommons.consumers.InstanceId;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.cataudit.TestProperties;
import com.catacumbas69.cataudit.model.AuditEvent;
import com.catacumbas69.cataudit.model.AuditValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

class AuditConsumerTest {

    private static final String EVENT = """
            {"specversion":"1.0","id":"e1","source":"/catacumbas69/cataponte",
             "type":"com.catacumbas69.cataponte.bridge.created.v1","subject":"b1","time":"2026-10-31T19:00:00Z",
             "datacontenttype":"application/json",
             "data":{"actor":"10.0.0.1","after":{"name":"fanout","source.topic":"persistent://a/b/c"}}}
            """;

    private final AuditStore store = mock(AuditStore.class);
    private final ConsumerControl control = new ConsumerControl(mock(PulsarClient.class), "cataudit",
            new InstanceId("cataudit"), List.of(), mock(EventPublisher.class), mock(ScheduledExecutorService.class),
            new CatacommonsProperties.Consumers(true, Duration.ofSeconds(10), Duration.ofSeconds(5), 10),
            Clock.systemUTC());
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SharedSettings> noSettings = mock(ObjectProvider.class);
    private final AuditConsumer consumer = new AuditConsumer(mock(PulsarClient.class), TestProperties.properties(),
            store, new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-10-31T20:00:00Z"), ZoneOffset.UTC), new CatacommonsScheduler(),
            new SimpleMeterRegistry(), control, mock(DeadLetterWriter.class), noSettings, commons());

    @Test
    void storesACloudEvent() {
        when(store.insert(any())).thenReturn(true);

        assertThat(consumer.handle(bytes(EVENT))).isEqualTo(AuditConsumer.Outcome.STORED);

        ArgumentCaptor<AuditEvent> stored = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).insert(stored.capture());
        assertThat(stored.getValue().type()).isEqualTo("com.catacumbas69.cataponte.bridge.created.v1");
        assertThat(stored.getValue().after()).containsExactly(new AuditValue("name", "fanout"),
                new AuditValue("source.topic", "persistent://a/b/c"));
    }

    @Test
    void acknowledgesADuplicate() {
        when(store.insert(any())).thenReturn(false);

        assertThat(consumer.handle(bytes(EVENT))).isEqualTo(AuditConsumer.Outcome.DUPLICATE);
    }

    @Test
    void sendsWhatIsNotACloudEventStraightToTheDeadLetterTopic() {
        assertThat(consumer.handle(bytes("not json"))).isEqualTo(AuditConsumer.Outcome.DEAD_LETTER);
        assertThat(consumer.handle(bytes("{\"type\":\"x\"}"))).isEqualTo(AuditConsumer.Outcome.DEAD_LETTER);
        verify(store, never()).insert(any());
    }

    @Test
    void pausesAndAsksForRedelivery_whenMongoIsDown() {
        when(store.insert(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(consumer.handle(bytes(EVENT))).isEqualTo(AuditConsumer.Outcome.RETRY);
        assertThat(consumer.gate().state()).isEqualTo(ConsumerGate.PAUSED_AUTO);
        assertThat(consumer.gate().reason()).contains("MongoDB unavailable");
    }

    @Test
    void doesNotPause_onAnErrorOfTheMessageItself() {
        when(store.insert(any())).thenThrow(new IllegalStateException("mapping failed"));

        consumer.handle(bytes(EVENT));

        assertThat(consumer.gate().isPaused()).isFalse();
    }

    @Test
    void asksForRedelivery_onAnyOtherStoreError() {
        when(store.insert(any())).thenThrow(new IllegalStateException("mapping failed"));

        assertThat(consumer.handle(bytes(EVENT))).isEqualTo(AuditConsumer.Outcome.RETRY);
    }

    private static CatacommonsProperties commons() {
        CatacommonsProperties properties = mock(CatacommonsProperties.class);
        when(properties.consumers()).thenReturn(
                new CatacommonsProperties.Consumers(true, Duration.ofSeconds(10), Duration.ofSeconds(5), 10));
        return properties;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
