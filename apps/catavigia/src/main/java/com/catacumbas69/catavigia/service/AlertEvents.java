package com.catacumbas69.catavigia.service;

import com.catacumbas69.catacommons.consumers.InstanceId;
import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catavigia.model.Alert;
import com.catacumbas69.catavigia.model.ArchivedAlert;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Publishes Catavigia's events on {@code catacumbas69/catavigia/events} (orch.md decision 48), which the
 * {@code audit-catavigia} bridge carries to Cataudit: {@code alert.opened}, {@code alert.seen},
 * {@code alert.closed} (subject = the alert's key) and {@code limit.changed}.
 */
@Component
public class AlertEvents {

    /** Entity of alert events. */
    public static final String ALERT = "alert";
    /** Entity of limit events. */
    public static final String LIMIT = "limit";

    private final EventPublisher events;
    private final String actor;

    /**
     * Creates the publisher.
     *
     * @param events catacommons' publisher
     * @param instance this replica, the actor of what Catavigia does by itself
     */
    public AlertEvents(EventPublisher events, InstanceId instance) {
        this.events = events;
        this.actor = "catavigia@" + instance.id();
    }

    /**
     * Returns who Catavigia's own actions are attributed to.
     *
     * @return {@code catavigia@<instance>}
     */
    public String actor() {
        return actor;
    }

    /**
     * Publishes {@code alert.opened}.
     *
     * @param alert the alert
     */
    public void opened(Alert alert) {
        events.publish(ALERT, "opened", alert.key(), EventData.action(actor, describe(alert)));
    }

    /**
     * Publishes {@code alert.seen}.
     *
     * @param alert the alert, marked as seen
     */
    public void seen(Alert alert) {
        events.publish(ALERT, "seen", alert.key(), EventData.action(alert.seenBy(), describe(alert)));
    }

    /**
     * Publishes {@code alert.closed}.
     *
     * @param archived the closed alert
     */
    public void closed(ArchivedAlert archived) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", archived.id());
        details.put("type", archived.type());
        details.put("subject", archived.subject());
        details.put("severity", archived.severity());
        details.put("openedAt", archived.openedAt());
        details.put("closedAt", archived.closedAt());
        details.put("durationSeconds", archived.durationSeconds());
        events.publish(ALERT, "closed", archived.key(), EventData.action(actor, details));
    }

    /**
     * Publishes {@code limit.changed}.
     *
     * @param subject {@code <topic>|<subscription>}
     * @param who client address
     * @param before limit before, or {@code null} for the global default
     * @param after limit after, or {@code null} for the global default
     */
    public void limitChanged(String subject, String who, Long before, Long after) {
        events.publish(LIMIT, "changed", subject, EventData.change(who,
                Map.of("limit", before == null ? "global" : before.toString()),
                Map.of("limit", after == null ? "global" : after.toString()), null));
    }

    private static Map<String, Object> describe(Alert alert) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", alert.id());
        details.put("type", alert.type());
        details.put("subject", alert.subject());
        details.put("severity", alert.severity());
        details.put("openedAt", alert.openedAt());
        if (alert.details() != null) {
            details.put("details", alert.details());
        }
        return details;
    }
}
