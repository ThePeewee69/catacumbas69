package com.catacumbas69.catacommons.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.schema.SchemaType;
import org.junit.jupiter.api.Test;

/**
 * The record must work with Pulsar's JSON schema: schema derived by reflection, JSON written with the
 * CloudEvents attribute names, and read back unchanged.
 */
class CloudEventSchemaTest {

    private final Schema<CloudEvent> schema = Schema.JSON(CloudEvent.class);

    private final CloudEvent event = new CloudEvent("1.0", "0b5c", "/catacumbas69/catapulsar",
            "com.catacumbas69.catapulsar.topic.created.v1", "persistent://a/b/c", "2026-10-31T18:00:00Z",
            "application/json", new EventData("10.0.0.1", null, Map.of("value", "60"), Map.of("count", "5")));

    @Test
    void schema_isJson_andDescribesEveryAttribute() {
        assertThat(schema.getSchemaInfo().getType()).isEqualTo(SchemaType.JSON);
        String definition = schema.getSchemaInfo().getSchemaDefinition();
        assertThat(definition).contains("\"specversion\"", "\"subject\"", "\"data\"", "\"actor\"", "\"details\"");
    }

    @Test
    void encode_writesCloudEventsJson_andDecodesBack() {
        byte[] bytes = schema.encode(event);

        String json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).contains("\"specversion\":\"1.0\"",
                "\"type\":\"com.catacumbas69.catapulsar.topic.created.v1\"", "\"actor\":\"10.0.0.1\"");
        assertThat(schema.decode(bytes)).isEqualTo(event);
    }
}
