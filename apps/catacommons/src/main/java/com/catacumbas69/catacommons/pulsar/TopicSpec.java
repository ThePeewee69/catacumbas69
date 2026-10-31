package com.catacumbas69.catacommons.pulsar;

import org.apache.pulsar.client.api.Schema;

/**
 * A topic an app owns and creates at start-up in its own namespace (ADR 0008). The event topic is
 * always created; an app declares any other topic as a {@code TopicSpec} bean.
 *
 * @param localName name inside the app's namespace, e.g. {@code events}
 * @param compacted {@code true} for a key → latest value topic: compaction on and infinite retention
 *                  (ADR 0011), set as topic policies
 * @param schema schema to register, or {@code null} to leave it to the first producer
 */
public record TopicSpec(String localName, boolean compacted, Schema<?> schema) {

    /**
     * Declares a normal topic; it follows the namespace's retention and backlog quota.
     *
     * @param localName name inside the namespace
     * @param schema schema to register
     * @return the spec
     */
    public static TopicSpec of(String localName, Schema<?> schema) {
        return new TopicSpec(localName, false, schema);
    }

    /**
     * Declares a compacted topic with infinite retention.
     *
     * @param localName name inside the namespace
     * @param schema schema to register
     * @return the spec
     */
    public static TopicSpec compacted(String localName, Schema<?> schema) {
        return new TopicSpec(localName, true, schema);
    }
}
