package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Everything Catasonarr does is an event on its one topic, {@code catacumbas69/catasonarr/events}, which Cataudit
 * stores (orch.md decision 109). Types are {@code com.catacumbas69.catasonarr.<entity>.<action>.v1}.
 */
@Component
public class CatasonarrEvents {

    /** The actor of what Catasonarr does by itself (the 12-hourly refresh). */
    public static final String SELF = "catasonarr";

    private final EventPublisher events;

    /**
     * Creates the publisher.
     *
     * @param events catacommons' publisher
     */
    public CatasonarrEvents(EventPublisher events) {
        this.events = events;
    }

    /**
     * Publishes a change.
     *
     * @param entity e.g. {@code series}
     * @param action e.g. {@code added}
     * @param subject e.g. the series' title and id
     * @param actor who did it
     * @param before state before, or {@code null}
     * @param after state after, or {@code null}
     */
    public void change(String entity, String action, String subject, String actor, Map<String, ?> before,
                       Map<String, ?> after) {
        events.publish(entity, action, subject, EventData.change(actor, before, after, null));
    }

    /**
     * Publishes an action that changes no stored state, or whose details say it all.
     *
     * @param entity e.g. {@code command}
     * @param action e.g. {@code received}
     * @param subject e.g. the command's name
     * @param actor who did it
     * @param details what to keep
     */
    public void action(String entity, String action, String subject, String actor, Map<String, ?> details) {
        events.publish(entity, action, subject, EventData.action(actor, details));
    }
}
