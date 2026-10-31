package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catapulsar.model.AuditEntry;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.AuditRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Writes and reads the audit log, and publishes every audited action as an event on
 * {@code catacumbas69/catapulsar/events} (ADR 0008).
 *
 * <p>Writes are asynchronous and best-effort (RES-11): the user's action never waits for MongoDB or Pulsar
 * and never fails because auditing failed; failures are logged. Until Cataudit exists (phase 2) both the
 * {@code audit_logs} entry and the event are written.
 */
@Service
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repository;
    private final ExecutorService executor;
    private final EventPublisher events;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param repository audit storage
     * @param executor executor for asynchronous writes
     * @param events publishes the matching events
     * @param clock time source for entry timestamps
     */
    public AuditService(AuditRepository repository, @Qualifier("auditExecutor") ExecutorService executor,
                        EventPublisher events, Clock clock) {
        this.repository = repository;
        this.executor = executor;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Records an action without blocking the caller.
     *
     * @param user client IP address
     * @param action what happened
     * @param topic topic involved, or {@code null}
     * @param details extra details, or {@code null}
     */
    public void record(String user, AuditAction action, String topic, Map<String, Object> details) {
        recordMessage(user, action, topic, null, null, details);
    }

    /**
     * Records an action that involves a message payload, without blocking the caller.
     *
     * @param user client IP address
     * @param action what happened
     * @param topic topic involved
     * @param payload message payload, or {@code null}
     * @param key message key, or {@code null}
     * @param details extra details, or {@code null}
     */
    public void recordMessage(String user, AuditAction action, String topic, String payload, String key,
                              Map<String, Object> details) {
        AuditEntry entry = new AuditEntry(clock.instant(), user, action.name(), topic, payload, key, details);
        try {
            executor.execute(() -> save(entry));
        } catch (RejectedExecutionException e) {
            LOG.warn("Audit dropped during shutdown: action={} topic={}", action, topic);
        }
        publish(user, action, topic, payload, key, details);
    }

    /**
     * Returns the most recent entries, newest first.
     *
     * @param page zero-based page number
     * @param size entries per page
     * @return the entries
     */
    public List<AuditEntry> recent(int page, int size) {
        return repository.findAllByOrderByTimestampDesc(PageRequest.of(page, size)).getContent();
    }

    private void publish(String user, AuditAction action, String topic, String payload, String key,
                         Map<String, Object> details) {
        try {
            if (action == AuditAction.CHANGE_SETTING && details != null) {
                publishSettingChanged(user, details);
                return;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            if (details != null) {
                values.putAll(details);
            }
            if (key != null) {
                values.put("key", key);
            }
            if (payload != null) {
                values.put("payload", payload);
            }
            events.publish(action.entity(), action.verb(), topic, EventData.action(user, values));
        } catch (RuntimeException e) {
            LOG.warn("Event not published: action={} topic={}: {}", action, topic, e.getMessage());
        }
    }

    /** Subject is the setting's topic key; before/after hold {@code value}. */
    private void publishSettingChanged(String user, Map<String, Object> details) {
        String id = String.valueOf(details.get("setting"));
        events.publish(AuditAction.CHANGE_SETTING.entity(), AuditAction.CHANGE_SETTING.verb(),
                Setting.topicKey(id).orElse(id),
                EventData.change(user, value(details.get("from")), value(details.get("to")), Map.of("id", id)));
    }

    private static Map<String, Object> value(Object value) {
        return value == null ? null : Map.of("value", value);
    }

    private void save(AuditEntry entry) {
        try {
            repository.save(entry);
        } catch (RuntimeException e) {
            LOG.warn("Audit not stored (action={} topic={}): {}", entry.getAction(), entry.getTopic(), e.getMessage());
        }
    }
}
