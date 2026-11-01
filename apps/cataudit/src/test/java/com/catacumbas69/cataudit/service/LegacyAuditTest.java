package com.catacumbas69.cataudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.cataudit.model.AuditEvent;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class LegacyAuditTest {

    private static final Instant NOW = Instant.parse("2026-10-31T20:00:00Z");
    private static final Instant THEN = Instant.parse("2026-10-29T10:00:00Z");
    private static final ObjectId OID = new ObjectId("65f0c0ffee0000000000abcd");

    @Test
    void produce_byPulsario_becomesCatapulsarMessageProduced() {
        Document entry = new Document("_id", OID).append("timestamp", Date.from(THEN)).append("user", "10.0.0.1")
                .append("action", "PRODUCE").append("topic", "persistent://a/b/c").append("messagePayload", "{}")
                .append("messageKey", "k").append("topicProperties", new Document("properties", Map.of("x", "1")));

        AuditEvent event = LegacyAudit.toEvent(entry, NOW).orElseThrow();

        assertThat(event.id()).isEqualTo("legacy-65f0c0ffee0000000000abcd");
        assertThat(event.app()).isEqualTo("catapulsar");
        assertThat(event.type()).isEqualTo("com.catacumbas69.catapulsar.message.produced.v1");
        assertThat(event.subject()).isEqualTo("persistent://a/b/c");
        assertThat(event.time()).isEqualTo(THEN);
        assertThat(event.actor()).isEqualTo("10.0.0.1");
        assertThat(event.details()).containsEntry("key", "k").containsEntry("payload", "{}")
                .containsEntry("properties.x", "1");
        assertThat(event.legacy()).isTrue();
    }

    @Test
    void bridgeChange_byPassario_becomesCataponteBridgeEvent_withBeforeAndAfter() {
        Document details = new Document("bridgeId", "b1").append("name", "fanout")
                .append("before", new Document("destinations", List.of("persistent://a/b/d")))
                .append("after", new Document("destinations", List.of("persistent://a/b/e")));
        Document entry = new Document("_id", OID).append("timestamp", Date.from(THEN)).append("user", "10.0.0.1")
                .append("action", "UPDATE_BRIDGE").append("application", "passario")
                .append("topic", "persistent://a/b/c").append("topicProperties", details);

        AuditEvent event = LegacyAudit.toEvent(entry, NOW).orElseThrow();

        assertThat(event.app()).isEqualTo("cataponte");
        assertThat(event.type()).isEqualTo("com.catacumbas69.cataponte.bridge.updated.v1");
        assertThat(event.subject()).isEqualTo("b1");
        assertThat(event.before()).containsEntry("destinations", "persistent://a/b/d");
        assertThat(event.after()).containsEntry("destinations", "persistent://a/b/e");
        assertThat(event.details()).containsEntry("name", "fanout").doesNotContainKey("before");
    }

    @Test
    void settingChange_movesFromAndToIntoBeforeAndAfter() {
        Document entry = new Document("_id", OID).append("timestamp", Date.from(THEN)).append("user", "10.0.0.1")
                .append("action", "CHANGE_SETTING")
                .append("topicProperties", new Document("setting", "passario.url").append("from", "http://a")
                        .append("to", "http://b"));

        AuditEvent event = LegacyAudit.toEvent(entry, NOW).orElseThrow();

        assertThat(event.type()).isEqualTo("com.catacumbas69.catapulsar.setting.changed.v1");
        assertThat(event.subject()).isEqualTo("passario.url");
        assertThat(event.before()).containsEntry("value", "http://a");
        assertThat(event.after()).containsEntry("value", "http://b");
    }

    @Test
    void unknownAction_isKeptAsLegacy() {
        Document entry = new Document("_id", OID).append("action", "SOMETHING_OLD");

        AuditEvent event = LegacyAudit.toEvent(entry, NOW).orElseThrow();

        assertThat(event.entity()).isEqualTo("legacy");
        assertThat(event.action()).isEqualTo("something-old");
        assertThat(event.time()).isEqualTo(NOW);
    }
}
