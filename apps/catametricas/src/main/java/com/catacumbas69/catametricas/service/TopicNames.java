package com.catacumbas69.catametricas.service;

import java.util.regex.Pattern;

/**
 * Splits full Pulsar topic names ({@code persistent://tenant/namespace/local}) into their parts.
 *
 * @param tenant tenant name
 * @param namespace namespace name without the tenant
 * @param name local name, e.g. {@code orders-partition-3}
 * @param baseName local name without partition suffix, e.g. {@code orders}
 */
public record TopicNames(String tenant, String namespace, String name, String baseName) {

    private static final Pattern PARTITION_SUFFIX = Pattern.compile("-partition-\\d+$");
    private static final String SCHEME_SEPARATOR = "://";

    /**
     * Parses a full topic name.
     *
     * @param topic e.g. {@code persistent://home/media/orders-partition-0}
     * @return its parts
     * @throws IllegalArgumentException if the name is not {@code scheme://tenant/namespace/local}
     */
    public static TopicNames parse(String topic) {
        int scheme = topic.indexOf(SCHEME_SEPARATOR);
        String path = scheme < 0 ? topic : topic.substring(scheme + SCHEME_SEPARATOR.length());
        String[] parts = path.split("/", 3);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("Not a tenant/namespace/topic name: " + topic);
        }
        return new TopicNames(parts[0], parts[1], parts[2], PARTITION_SUFFIX.matcher(parts[2]).replaceFirst(""));
    }

    /**
     * Tells whether this is a Pulsar system topic such as {@code __change_events}.
     *
     * @return {@code true} if the local name starts with two underscores
     */
    public boolean isSystemTopic() {
        return name.startsWith("__");
    }
}
