package com.catacumbas69.catacommons.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventValuesTest {

    @Test
    void flatten_usesDottedKeys_joinsLists_andSkipsNulls() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("topic", "persistent://a/b/c");
        source.put("type", "Shared");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", "orders");
        values.put("source", source);
        values.put("destinations", List.of("persistent://a/b/d", "persistent://a/b/e"));
        values.put("enabled", true);
        values.put("description", null);

        assertThat(EventValues.flatten(values)).containsExactly(
                Map.entry("name", "orders"),
                Map.entry("source.topic", "persistent://a/b/c"),
                Map.entry("source.type", "Shared"),
                Map.entry("destinations", "persistent://a/b/d,persistent://a/b/e"),
                Map.entry("enabled", "true"));
    }

    @Test
    void flatten_keepsNull_andEmpty() {
        assertThat(EventValues.flatten(null)).isNull();
        assertThat(EventValues.flatten(new HashMap<String, Object>())).isEmpty();
    }
}
