package com.catacumbas69.catacommons.events;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns arbitrary values into the text maps of {@link EventData}.
 */
public final class EventValues {

    private EventValues() {
    }

    /**
     * Flattens a map: nested maps become dotted keys ({@code source.topic}), collections become
     * comma-separated text and {@code null} values are left out. Order is kept.
     *
     * @param values the values, or {@code null}
     * @return text values, or {@code null} when {@code values} is {@code null}
     */
    public static Map<String, String> flatten(Map<String, ?> values) {
        if (values == null) {
            return null;
        }
        Map<String, String> flat = new LinkedHashMap<>();
        values.forEach((key, value) -> put(flat, key, value));
        return flat;
    }

    private static void put(Map<String, String> flat, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> nested) {
            nested.forEach((nestedKey, nestedValue) -> put(flat, key + "." + nestedKey, nestedValue));
        } else if (value instanceof Collection<?> items) {
            flat.put(key, items.stream().map(String::valueOf).collect(Collectors.joining(",")));
        } else {
            flat.put(key, String.valueOf(value));
        }
    }
}
