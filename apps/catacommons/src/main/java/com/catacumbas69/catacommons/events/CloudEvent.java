package com.catacumbas69.catacommons.events;

/**
 * A CloudEvents 1.0 event in JSON structured mode (ADR 0008), the value of every message on a
 * {@code catacumbas69/<app>/events} topic. Pulsar registers a JSON schema derived from this record.
 *
 * <p>Field names are the CloudEvents attribute names, so the JSON is a valid CloudEvent as it is.
 *
 * @param specversion always {@value #SPEC_VERSION}
 * @param id unique id (UUID); consumers use it to ignore duplicates (ADR 0012)
 * @param source {@code /catacumbas69/<app>}
 * @param type {@code com.catacumbas69.<app>.<entity>.<action>.v1}
 * @param subject what the event is about (topic, bridge id, setting key…); also the message key
 * @param time when it happened, RFC 3339 in UTC
 * @param datacontenttype always {@value #CONTENT_TYPE}
 * @param data who did it and what changed
 */
public record CloudEvent(
        String specversion,
        String id,
        String source,
        String type,
        String subject,
        String time,
        String datacontenttype,
        EventData data) {

    /** CloudEvents specification version. */
    public static final String SPEC_VERSION = "1.0";

    /** Media type of {@link #data()}. */
    public static final String CONTENT_TYPE = "application/json";
}
