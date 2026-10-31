package com.catacumbas69.catacommons.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.catacumbas69.catacommons.pulsar.RetryingSender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-31T18:00:00Z");

    @SuppressWarnings("unchecked")
    private final RetryingSender<CloudEvent> sender = mock(RetryingSender.class);
    private final EventPublisher publisher = new EventPublisher("catapulsar", sender,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void publish_buildsCloudEvent_andSendsItKeyedBySubject() {
        EventData data = EventData.action("10.0.0.1", Map.of("subscription", "s1"));

        CloudEvent event = publisher.publish("subscription", "backlog-cleared", "persistent://a/b/c", data);

        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.source()).isEqualTo("/catacumbas69/catapulsar");
        assertThat(event.type()).isEqualTo("com.catacumbas69.catapulsar.subscription.backlog-cleared.v1");
        assertThat(event.subject()).isEqualTo("persistent://a/b/c");
        assertThat(event.time()).isEqualTo("2026-10-31T18:00:00Z");
        assertThat(event.datacontenttype()).isEqualTo("application/json");
        assertThat(event.id()).hasSize(36);
        assertThat(event.data()).isEqualTo(data);
        verify(sender).send("persistent://a/b/c", event);
    }

    @Test
    void publish_withoutSender_onlyBuildsTheEvent() {
        EventPublisher off = new EventPublisher("cataponte", null, Clock.systemUTC());

        CloudEvent event = off.publish("bridge", "created", "b1", EventData.action("10.0.0.1", null));

        assertThat(event.type()).isEqualTo("com.catacumbas69.cataponte.bridge.created.v1");
    }
}
