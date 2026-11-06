package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConnectionProbe;
import com.catacumbas69.catacommons.consumers.ConsumerControl;
import com.catacumbas69.catacommons.consumers.ConsumerGate;
import com.catacumbas69.catacommons.consumers.DeadLetterWriter;
import com.catacumbas69.catacommons.consumers.InstanceId;
import com.catacumbas69.catacommons.consumers.ProbeResult;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catametricas.TestProperties;
import com.catacumbas69.catametricas.model.CollectTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CollectTaskConsumerTest {

    private final StatsCollector collector = mock(StatsCollector.class);
    private final AtomicBoolean pulsarUp = new AtomicBoolean(true);
    private final ConnectionProbe pulsar = new ConnectionProbe() {
        @Override
        public String name() {
            return ConnectionProbe.PULSAR;
        }

        @Override
        public ProbeResult probe() {
            return pulsarUp.get() ? ProbeResult.up(2) : ProbeResult.down(60_000, "connection refused");
        }
    };
    private final ConsumerControl control = new ConsumerControl(mock(PulsarClient.class), "catametricas",
            new InstanceId("catametricas"), List.of(pulsar), mock(EventPublisher.class),
            mock(ScheduledExecutorService.class),
            new CatacommonsProperties.Consumers(true, Duration.ofSeconds(10), Duration.ofSeconds(5), 10),
            Clock.systemUTC());
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SharedSettings> noSettings = mock(ObjectProvider.class);
    private final CollectTaskConsumer consumer = new CollectTaskConsumer(mock(PulsarClient.class),
            TestProperties.properties(), collector, new ObjectMapper(), mock(CatacommonsScheduler.class),
            control, mock(DeadLetterWriter.class), noSettings, commons());

    @Test
    void pauses_whenATaskFailedBecausePulsarIsDown() {
        pulsarUp.set(false);

        consumer.pauseIfUnavailable();

        assertThat(consumer.gate().state()).isEqualTo(ConsumerGate.PAUSED_AUTO);
        assertThat(consumer.gate().reason()).contains("pulsar unavailable");
    }

    @Test
    void doesNotPause_whenTheTaskFailedOnItsOwn() {
        consumer.pauseIfUnavailable();

        assertThat(consumer.gate().isPaused()).isFalse();
    }

    @Test
    void runsTheTaskInTheMessage() {
        when(collector.handle(any())).thenReturn(StatsCollector.Outcome.DONE);
        String json = "{\"id\":\"home/media@2026-11-01T09:00:00Z\",\"namespace\":\"home/media\","
                + "\"bucket\":\"2026-11-01T09:00:00Z\",\"intervalSeconds\":60}";

        assertThat(consumer.handle(json.getBytes(StandardCharsets.UTF_8))).isEqualTo(StatsCollector.Outcome.DONE);

        verify(collector).handle(new CollectTask("home/media@2026-11-01T09:00:00Z", "home/media",
                "2026-11-01T09:00:00Z", 60));
    }

    @Test
    void passesNothing_whenTheMessageIsNotJson() {
        when(collector.handle(null)).thenReturn(StatsCollector.Outcome.INVALID);

        assertThat(consumer.handle("not json".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(StatsCollector.Outcome.INVALID);
    }

    private static CatacommonsProperties commons() {
        CatacommonsProperties properties = mock(CatacommonsProperties.class);
        when(properties.consumers()).thenReturn(
                new CatacommonsProperties.Consumers(true, Duration.ofSeconds(10), Duration.ofSeconds(5), 10));
        return properties;
    }
}
