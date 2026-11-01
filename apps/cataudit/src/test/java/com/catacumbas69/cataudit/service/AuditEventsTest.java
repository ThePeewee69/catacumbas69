package com.catacumbas69.cataudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.cataudit.model.AuditEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditEventsTest {

    private static final Instant NOW = Instant.parse("2026-10-31T20:00:00Z");

    @Test
    void from_splitsTheType_andKeepsTheData() {
        CloudEvent event = new CloudEvent("1.0", "e1", "/catacumbas69/catapulsar",
                "com.catacumbas69.catapulsar.subscription.backlog-cleared.v1", "persistent://a/b/c",
                "2026-10-31T19:00:00Z", "application/json",
                new EventData("10.0.0.1", null, null, Map.of("subscription", "s1")));

        AuditEvent stored = AuditEvents.from(event, NOW).orElseThrow();

        assertThat(stored.id()).isEqualTo("e1");
        assertThat(stored.app()).isEqualTo("catapulsar");
        assertThat(stored.entity()).isEqualTo("subscription");
        assertThat(stored.action()).isEqualTo("backlog-cleared");
        assertThat(stored.subject()).isEqualTo("persistent://a/b/c");
        assertThat(stored.time()).isEqualTo(Instant.parse("2026-10-31T19:00:00Z"));
        assertThat(stored.actor()).isEqualTo("10.0.0.1");
        assertThat(stored.details()).containsEntry("subscription", "s1");
        assertThat(stored.receivedAt()).isEqualTo(NOW);
    }

    @Test
    void from_keepsUnknownTypes_andUsesReceivedTimeWhenTimeIsInvalid() {
        CloudEvent event = new CloudEvent("1.0", "e2", "/catacumbas69/other", "something.else", "x", "yesterday",
                "application/json", null);

        AuditEvent stored = AuditEvents.from(event, NOW).orElseThrow();

        assertThat(stored.app()).isEqualTo("other");
        assertThat(stored.entity()).isEqualTo("unknown");
        assertThat(stored.action()).isEqualTo("something.else");
        assertThat(stored.time()).isEqualTo(NOW);
        assertThat(stored.actor()).isNull();
    }

    @Test
    void from_rejectsEventsWithoutId() {
        CloudEvent event = new CloudEvent("1.0", " ", "/catacumbas69/x", "t", "s", null, null, null);

        assertThat(AuditEvents.from(event, NOW)).isEmpty();
        assertThat(AuditEvents.from(null, NOW)).isEmpty();
    }
}
