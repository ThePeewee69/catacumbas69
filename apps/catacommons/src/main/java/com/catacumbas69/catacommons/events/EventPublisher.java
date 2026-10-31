package com.catacumbas69.catacommons.events;

import com.catacumbas69.catacommons.pulsar.RetryingSender;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Publishes this app's events to {@code catacumbas69/<app>/events} (ADR 0008).
 *
 * <p>Call it <em>after</em> the change is stored: publishing is asynchronous, retried with back-off, and
 * never fails or delays the caller; an event that cannot be published is logged at {@code ERROR} in full.
 * The message key is the event's subject, so events about the same thing stay in order.
 */
public class EventPublisher {

    /** Prefix of every event type. */
    public static final String TYPE_PREFIX = "com.catacumbas69.";

    /** Version suffix of every event type in use. */
    public static final String TYPE_VERSION = "v1";

    private final String app;
    private final RetryingSender<CloudEvent> sender;
    private final Clock clock;

    /**
     * Creates the publisher.
     *
     * @param app this app's name
     * @param sender sends to this app's event topic, or {@code null} to drop every event (publishing off)
     * @param clock time source for {@code time}
     */
    public EventPublisher(String app, RetryingSender<CloudEvent> sender, Clock clock) {
        this.app = app;
        this.sender = sender;
        this.clock = clock;
    }

    /**
     * Builds the type of an event of this app.
     *
     * @param app app name
     * @param entity what the event is about, e.g. {@code topic} or {@code bridge}
     * @param action what happened, past tense, e.g. {@code created} or {@code backlog-cleared}
     * @return {@code com.catacumbas69.<app>.<entity>.<action>.v1}
     */
    public static String type(String app, String entity, String action) {
        return TYPE_PREFIX + app + "." + entity + "." + action + "." + TYPE_VERSION;
    }

    /**
     * Builds an event and publishes it in the background. Never throws.
     *
     * @param entity what the event is about, e.g. {@code topic}
     * @param action what happened, e.g. {@code created}
     * @param subject the thing itself (topic name, bridge id…); also the message key
     * @param data who did it and what changed
     * @return the event, as published
     */
    public CloudEvent publish(String entity, String action, String subject, EventData data) {
        CloudEvent event = new CloudEvent(CloudEvent.SPEC_VERSION, UUID.randomUUID().toString(), source(),
                type(app, entity, action), subject, DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock)),
                CloudEvent.CONTENT_TYPE, data);
        if (sender != null) {
            sender.send(subject, event);
        }
        return event;
    }

    /**
     * Returns the {@code source} of this app's events.
     *
     * @return {@code /catacumbas69/<app>}
     */
    public String source() {
        return "/catacumbas69/" + app;
    }
}
