package com.catacumbas69.catacommons.pulsar;

import java.time.Duration;
import org.apache.pulsar.client.api.Schema;

/**
 * A topic an app owns and creates at start-up in its own namespace (ADR 0008). The event topic is
 * always created; an app declares any other topic as a {@code TopicSpec} bean.
 *
 * @param localName name inside the app's namespace, e.g. {@code events}
 * @param compacted {@code true} for a key → latest value topic: compaction on and its own retention (ADR 0011),
 *                  set as topic policies
 * @param schema schema to register, or {@code null} to leave it to the first producer
 * @param retention for a compacted topic, how long its messages are kept ({@code null}: for ever); the compacted
 *                  view (the latest value of each key) is kept regardless. Unused for a normal topic
 */
public record TopicSpec(String localName, boolean compacted, Schema<?> schema, Duration retention) {

    /**
     * Declares a normal topic; it follows the namespace's retention and backlog quota.
     *
     * @param localName name inside the namespace
     * @param schema schema to register
     * @return the spec
     */
    public static TopicSpec of(String localName, Schema<?> schema) {
        return new TopicSpec(localName, false, schema, null);
    }

    /**
     * Declares a compacted topic that keeps every message for ever.
     *
     * @param localName name inside the namespace
     * @param schema schema to register
     * @return the spec
     */
    public static TopicSpec compacted(String localName, Schema<?> schema) {
        return new TopicSpec(localName, true, schema, null);
    }

    /**
     * Declares a compacted topic that keeps its messages for a while; the compacted view stays.
     *
     * @param localName name inside the namespace
     * @param schema schema to register
     * @param retention how long messages are kept
     * @return the spec
     */
    public static TopicSpec compacted(String localName, Schema<?> schema, Duration retention) {
        return new TopicSpec(localName, true, schema, retention);
    }
}
