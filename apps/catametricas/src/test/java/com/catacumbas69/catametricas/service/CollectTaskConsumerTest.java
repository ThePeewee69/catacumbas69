package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catametricas.TestProperties;
import com.catacumbas69.catametricas.model.CollectTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;

class CollectTaskConsumerTest {

    private final StatsCollector collector = mock(StatsCollector.class);
    private final CollectTaskConsumer consumer = new CollectTaskConsumer(mock(PulsarClient.class),
            TestProperties.properties(), collector, new ObjectMapper(), mock(CatacommonsScheduler.class));

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
}
