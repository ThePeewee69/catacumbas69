package com.catacumbas69.catacommons.events;

import java.util.Map;

/**
 * The {@code data} of every event (ADR 0008). Values are text so that one Pulsar schema describes every
 * event: nested values are flattened with dotted keys and lists are joined with commas (see
 * {@link EventValues#flatten(Map)}).
 *
 * @param actor who did it: the client IP address until authentication exists, or the app name for
 *              changes an app makes on its own
 * @param before state before the change, or {@code null}
 * @param after state after the change, or {@code null}
 * @param details anything else worth keeping (subscription, count, HTTP status…), or {@code null}
 */
public record EventData(
        String actor,
        Map<String, String> before,
        Map<String, String> after,
        Map<String, String> details) {

    /**
     * Creates the data of an action that changes no stored state (a peek, a produce…).
     *
     * @param actor who did it
     * @param details values to keep, flattened; may be {@code null}
     * @return the data
     */
    public static EventData action(String actor, Map<String, ?> details) {
        return new EventData(actor, null, null, EventValues.flatten(details));
    }

    /**
     * Creates the data of a change.
     *
     * @param actor who made it
     * @param before state before, or {@code null} when something was created
     * @param after state after, or {@code null} when something was deleted
     * @param details anything else, or {@code null}
     * @return the data
     */
    public static EventData change(String actor, Map<String, ?> before, Map<String, ?> after,
                                   Map<String, ?> details) {
        return new EventData(actor, EventValues.flatten(before), EventValues.flatten(after),
                EventValues.flatten(details));
    }
}
