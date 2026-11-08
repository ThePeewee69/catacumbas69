package com.catacumbas69.catavigia.service;

import com.catacumbas69.catacommons.alerts.AlertSetting;
import com.catacumbas69.catacommons.consumers.InstanceId;
import com.catacumbas69.catacommons.consumers.ProbeResult;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.model.Alert;
import com.catacumbas69.catavigia.model.AlertTypes;
import com.catacumbas69.catavigia.model.ArchivedAlert;
import com.catacumbas69.catavigia.model.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code mongodb.down} of this replica (orch.md decision 76). No task can report it, since claiming a task needs
 * MongoDB, so every replica pings its own MongoDB once per round and keeps the alert <b>in memory</b>: it is
 * returned by the API, counted in the badge and the metric, and published as {@code alert.opened} (subject = the
 * replica). It opens and closes with the same debounce as the other alerts; once MongoDB answers again it is
 * written to the archive with its real opening time, and {@code alert.closed} is published.
 */
@Component
public class MongoWatch {

    private static final Logger LOG = LoggerFactory.getLogger(MongoWatch.class);

    private final MongoConnectionProbe probe;
    private final MongoTemplate mongoTemplate;
    private final AlertEvents events;
    private final AlertMetrics metrics;
    private final SharedSettings settings;
    private final String subject;
    private final Clock clock;
    private int failures;
    private int answers;
    private Instant firstFailure;
    private String lastError;
    private volatile Alert alert;

    /**
     * Creates the watch.
     *
     * @param probe pings MongoDB
     * @param mongoTemplate template, to archive the alert
     * @param events publishes the alert's events
     * @param metrics refreshed after every round
     * @param settings the settings topic, for the debounce
     * @param instance this replica, the alert's subject
     * @param clock time source
     */
    public MongoWatch(MongoConnectionProbe probe, MongoTemplate mongoTemplate, AlertEvents events,
                      AlertMetrics metrics, ObjectProvider<SharedSettings> settings, InstanceId instance,
                      Clock clock) {
        this.probe = probe;
        this.mongoTemplate = mongoTemplate;
        this.events = events;
        this.metrics = metrics;
        this.settings = settings.getIfAvailable();
        this.subject = "catavigia@" + instance.id();
        this.clock = clock;
    }

    /**
     * Returns this replica's {@code mongodb.down}, if open.
     *
     * @return the alert, kept in memory
     */
    public Optional<Alert> alert() {
        return Optional.ofNullable(alert);
    }

    /** Pings MongoDB once, opens or closes the alert, then refreshes the metric. Never throws. */
    public synchronized void tick() {
        int debounce = (int) AlertSetting.DEBOUNCE.value(settings);
        ProbeResult result = probe.probe();
        Instant now = clock.instant();
        if (result.up()) {
            failures = 0;
            answers++;
            if (alert != null && answers >= debounce) {
                close(now);
            }
        } else {
            answers = 0;
            if (failures == 0) {
                firstFailure = now;
            }
            failures++;
            lastError = result.error();
            if (alert == null && failures >= debounce) {
                open();
            } else if (alert != null) {
                alert = withDetails(alert, now);
            }
        }
        metrics.refresh(alert);
    }

    private void open() {
        Instant now = clock.instant();
        Alert opened = new Alert(UUID.randomUUID().toString(), Schema.key(AlertTypes.MONGODB_DOWN, subject),
                AlertTypes.MONGODB_DOWN, subject, AlertTypes.CRITICAL, details(), firstFailure, now, null, null);
        alert = opened;
        LOG.error("Alert opened: critical mongodb.down {} ({})", subject, lastError);
        events.opened(opened);
    }

    private void close(Instant now) {
        ArchivedAlert archived = ArchivedAlert.of(alert, now);
        try {
            mongoTemplate.save(archived);
        } catch (RuntimeException e) {
            LOG.warn("mongodb.down not archived yet, trying again next round: {}", e.getMessage());
            return;
        }
        alert = null;
        LOG.info("Alert closed after {} s: mongodb.down {}", archived.durationSeconds(), subject);
        events.closed(archived);
    }

    private Alert withDetails(Alert current, Instant now) {
        return new Alert(current.id(), current.key(), current.type(), current.subject(), current.severity(),
                details(), current.openedAt(), now, current.seenBy(), current.seenAt());
    }

    private Map<String, String> details() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("error", String.valueOf(lastError));
        details.put("failedChecks", Integer.toString(failures));
        details.put("local", "kept in memory by " + subject + " until MongoDB answers");
        return details;
    }
}
