package com.catacumbas69.cataudit.service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Copies the old shared {@code audit_logs} collection (ADR 0006) into {@code cataudit_events} at every start
 * (ADR 0009). Copied entries keep their time, user, action and details and get the id {@code legacy-<ObjectId>},
 * so a second copy only skips them. {@code audit_logs} itself is never changed; delete it by hand once it is no
 * longer needed. Runs in the background and retries every 30 s while MongoDB is down.
 */
@Component
public class LegacyAuditMigration {

    /** The old collection. */
    public static final String LEGACY_COLLECTION = "audit_logs";

    private static final Logger LOG = LoggerFactory.getLogger(LegacyAuditMigration.class);
    private static final Duration RETRY = Duration.ofSeconds(30);

    private final MongoTemplate mongoTemplate;
    private final AuditStore store;
    private final Clock clock;

    /**
     * Creates the migration.
     *
     * @param mongoTemplate reads {@code audit_logs}
     * @param store writes {@code cataudit_events}
     * @param clock time source for {@code receivedAt}
     */
    public LegacyAuditMigration(MongoTemplate mongoTemplate, AuditStore store, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.store = store;
        this.clock = clock;
    }

    /** Starts the copy in the background once the application is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void copyInBackground() {
        Thread.ofVirtual().name("legacy-audit-migration").start(this::copyUntilDone);
    }

    private void copyUntilDone() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                copy();
                return;
            } catch (RuntimeException e) {
                LOG.warn("audit_logs not copied yet, retrying in {} s: {}", RETRY.toSeconds(), e.getMessage());
            }
            try {
                Thread.sleep(RETRY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Copies every entry not copied yet.
     *
     * @return entries copied now
     */
    long copy() {
        if (!mongoTemplate.collectionExists(LEGACY_COLLECTION)) {
            LOG.info("No audit_logs collection, nothing to copy");
            return 0;
        }
        AtomicLong copied = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        try (Stream<Document> entries = mongoTemplate.stream(new Query(), Document.class, LEGACY_COLLECTION)) {
            entries.forEach(entry -> LegacyAudit.toEvent(entry, clock.instant()).ifPresent(event -> {
                if (store.insert(event)) {
                    copied.incrementAndGet();
                } else {
                    skipped.incrementAndGet();
                }
            }));
        }
        LOG.info("audit_logs copied to cataudit_events: {} new, {} already there", copied.get(), skipped.get());
        return copied.get();
    }
}
