package com.catacumbas69.pulsario.service;

import com.catacumbas69.pulsario.model.AuditEntry;
import com.catacumbas69.pulsario.repository.AuditRepository;
import java.time.Clock;
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
 * Writes and reads the audit log.
 *
 * <p>Writes are asynchronous and best-effort (RES-11): the user's action never waits for MongoDB and
 * never fails because auditing failed; failures are logged at WARN.
 */
@Service
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repository;
    private final ExecutorService executor;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param repository audit storage
     * @param executor executor for asynchronous writes
     * @param clock time source for entry timestamps
     */
    public AuditService(AuditRepository repository, @Qualifier("auditExecutor") ExecutorService executor,
                        Clock clock) {
        this.repository = repository;
        this.executor = executor;
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

    private void save(AuditEntry entry) {
        try {
            repository.save(entry);
        } catch (RuntimeException e) {
            LOG.warn("Audit not stored (action={} topic={}): {}", entry.getAction(), entry.getTopic(), e.getMessage());
        }
    }
}
