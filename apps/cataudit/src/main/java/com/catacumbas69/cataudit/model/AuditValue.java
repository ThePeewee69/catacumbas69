package com.catacumbas69.cataudit.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One key and value of an event's {@code before}, {@code after} or {@code details}. They are stored as a list of
 * pairs, not as a map, because the keys of flattened values contain dots ({@code source.topic}) and MongoDB map
 * keys cannot.
 *
 * @param key the key, e.g. {@code source.topic}
 * @param value the value
 */
public record AuditValue(String key, String value) {

    /**
     * Turns a map into pairs, in the map's order.
     *
     * @param values the map, or {@code null}
     * @return the pairs, or {@code null} for {@code null}
     */
    public static List<AuditValue> listOf(Map<String, String> values) {
        if (values == null) {
            return null;
        }
        List<AuditValue> pairs = new ArrayList<>(values.size());
        values.forEach((key, value) -> pairs.add(new AuditValue(key, value)));
        return pairs;
    }

    /**
     * Turns pairs back into a map, in their order.
     *
     * @param pairs the pairs, or {@code null}
     * @return the map, or {@code null} for {@code null}
     */
    public static Map<String, String> mapOf(List<AuditValue> pairs) {
        if (pairs == null) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        pairs.forEach(pair -> values.put(pair.key(), pair.value()));
        return values;
    }
}
