package com.catacumbas69.cataudit.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class AuditValueTest {

    @Test
    void mapAndPairs_roundTrip_inOrder() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("source.topic", "persistent://a/b/c");
        values.put("name", "fanout");

        List<AuditValue> pairs = AuditValue.listOf(values);

        assertThat(pairs).containsExactly(new AuditValue("source.topic", "persistent://a/b/c"),
                new AuditValue("name", "fanout"));
        assertThat(AuditValue.mapOf(pairs)).containsExactly(Map.entry("source.topic", "persistent://a/b/c"),
                Map.entry("name", "fanout"));
    }

    @Test
    void nullStaysNull() {
        assertThat(AuditValue.listOf(null)).isNull();
        assertThat(AuditValue.mapOf(null)).isNull();
    }

    /** Spring Data refuses map keys with dots; keys inside pairs are plain values, so the event is written. */
    @Test
    void eventWithDottedKeys_isWrittenAndReadBackByTheMongoConverter() {
        // as Spring Boot sets it up: Instant and the other JSR-310 types are simple types, written as dates
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        AuditEvent event = new AuditEvent("e1", "/catacumbas69/cataponte",
                "com.catacumbas69.cataponte.bridge.created.v1", "cataponte", "bridge", "created", "b1",
                Instant.parse("2026-11-01T19:06:19Z"), "cataponte", null,
                List.of(new AuditValue("source.topic", "persistent://catacumbas69/catapulsar/events")),
                null, Instant.parse("2026-11-01T19:06:19Z"));

        Document document = new Document();
        converter.write(event, document);

        assertThat(document.getList("after", Document.class)).singleElement()
                .satisfies(pair -> assertThat(pair.getString("key")).isEqualTo("source.topic"));
        assertThat(converter.read(AuditEvent.class, document)).isEqualTo(event);
    }
}
