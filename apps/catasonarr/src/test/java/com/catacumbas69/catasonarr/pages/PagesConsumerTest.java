package com.catacumbas69.catasonarr.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConsumerControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;

class PagesConsumerTest {

    private static final Instant NOW = Instant.parse("2026-11-24T22:00:00Z");

    private final ConsumerControl control = mock(ConsumerControl.class, RETURNS_DEEP_STUBS);
    private final PagesConsumer consumer = consumer();

    @Test
    void handle_aRecentChange_goesToThePages() {
        assertThat(consumer.handle(event("2026-11-24T21:59:30Z")))
                .hasValueSatisfying(json -> assertThat(json).contains("\"entity\":\"series\"", "\"seriesId\":12"));
    }

    @Test
    void handle_aChangeOlderThanAMinute_orNotAnEvent_goesNowhere() {
        assertThat(consumer.handle(event("2026-11-24T21:58:00Z"))).isEmpty();
        assertThat(consumer.handle("not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void subscribes_asThisReplica() {
        assertThat(PagesTopics.subscription("catasonarr", "catasonarr-7d9f-x2")).isEqualTo(
                "catasonarr-pages-catasonarr-7d9f-x2");
        assertThat(PagesTopics.topic("catasonarr")).isEqualTo("persistent://catacumbas69/catasonarr/pages");
        assertThat(PagesTopics.bridge("catasonarr").sourceTopic())
                .isEqualTo("persistent://catacumbas69/catasonarr/events");
    }

    private PagesConsumer consumer() {
        when(control.instance()).thenReturn("pod-1");
        return new PagesConsumer(mock(PulsarClient.class), mock(PulsarAdmin.class), new ObjectMapper(),
                mock(CatacommonsScheduler.class), control, new PageUpdates(), "catasonarr",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static byte[] event(String time) {
        return ("{\"specversion\":\"1.0\",\"id\":\"e1\",\"source\":\"catasonarr\","
                + "\"type\":\"com.catacumbas69.catasonarr.series.added.v1\",\"subject\":\"Andor (12)\","
                + "\"time\":\"" + time + "\",\"datacontenttype\":\"application/json\","
                + "\"data\":{\"actor\":\"peewee69\",\"after\":{\"id\":\"12\"}}}").getBytes(StandardCharsets.UTF_8);
    }
}
