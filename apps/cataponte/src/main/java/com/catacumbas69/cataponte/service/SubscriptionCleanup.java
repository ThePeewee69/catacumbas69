package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.model.SubscriptionCleanupDocument;
import com.catacumbas69.cataponte.repository.BridgeRepository;
import com.catacumbas69.cataponte.repository.SubscriptionCleanupRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.policies.data.SubscriptionStats;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the Pulsar subscription of a deleted bridge, or the old one of a bridge that now reads another topic or
 * subscription (orch.md decision 60), unless another bridge still uses it.
 *
 * <p>The deletion is queued in MongoDB and tried after {@code cataponte.subscription-cleanup.delay}, so the
 * bridge's workers on every replica have stopped first: deleting while they are connected would make them
 * subscribe again. Without {@code force}, Pulsar refuses while consumers are connected; that, and any other
 * failure, is tried again later. Each replica tries the due deletions; a short lease in the document makes one
 * replica work on each. When a subscription is gone, a {@code subscription.deleted} event is published (Cataudit).
 */
@Component
public class SubscriptionCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionCleanup.class);

    private final SubscriptionCleanupRepository repository;
    private final BridgeRepository bridges;
    private final MongoTemplate mongoTemplate;
    private final PulsarAdmin admin;
    private final AuditLog audit;
    private final Clock clock;
    private final Duration delay;
    private final Duration retryDelay;

    /**
     * Creates the cleanup.
     *
     * @param repository pending deletions
     * @param bridges bridge definitions, to keep a subscription another bridge uses
     * @param mongoTemplate claims a pending deletion
     * @param admin deletes subscriptions
     * @param audit publishes the deletions
     * @param delay wait before the first try
     * @param retryDelay wait between tries
     */
    @Autowired
    public SubscriptionCleanup(SubscriptionCleanupRepository repository, BridgeRepository bridges,
                               MongoTemplate mongoTemplate, PulsarAdmin admin, AuditLog audit,
                               @Value("${cataponte.subscription-cleanup.delay:PT30S}") Duration delay,
                               @Value("${cataponte.subscription-cleanup.retry-delay:PT1M}") Duration retryDelay) {
        this(repository, bridges, mongoTemplate, admin, audit, Clock.systemUTC(), delay, retryDelay);
    }

    SubscriptionCleanup(SubscriptionCleanupRepository repository, BridgeRepository bridges,
                        MongoTemplate mongoTemplate, PulsarAdmin admin, AuditLog audit, Clock clock, Duration delay,
                        Duration retryDelay) {
        this.repository = repository;
        this.bridges = bridges;
        this.mongoTemplate = mongoTemplate;
        this.admin = admin;
        this.audit = audit;
        this.clock = clock;
        this.delay = delay;
        this.retryDelay = retryDelay;
    }

    /**
     * Queues the deletion of a bridge's subscription. Replaces a pending deletion of the same subscription.
     *
     * @param bridge the bridge as it was (its source topic and subscription)
     * @param actor who deleted or changed the bridge
     */
    public void schedule(BridgeDocument bridge, String actor) {
        if (bridge.getSource() == null || bridge.getSource().getTopic() == null
                || bridge.getSource().getSubscription() == null) {
            return;
        }
        Instant now = clock.instant();
        SubscriptionCleanupDocument cleanup = new SubscriptionCleanupDocument();
        cleanup.setId(SubscriptionCleanupDocument.idOf(bridge.getSource().getTopic(),
                bridge.getSource().getSubscription()));
        cleanup.setTopic(bridge.getSource().getTopic());
        cleanup.setSubscription(bridge.getSource().getSubscription());
        cleanup.setBridgeId(bridge.getId());
        cleanup.setBridgeName(bridge.getName());
        cleanup.setActor(actor);
        cleanup.setRequestedAt(now);
        cleanup.setNotBefore(now.plus(delay));
        try {
            repository.save(cleanup);
        } catch (RuntimeException e) {
            LOG.error("Subscription {} on {} not queued for deletion: {}", cleanup.getSubscription(),
                    cleanup.getTopic(), e.getMessage());
        }
    }

    /** Tries the due deletions. */
    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${cataponte.subscription-cleanup.interval:PT15S}")
    public void runDue() {
        List<SubscriptionCleanupDocument> due;
        try {
            due = repository.findByNotBeforeLessThanEqual(clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("Pending subscription deletions not read: {}", e.getMessage());
            return;
        }
        for (SubscriptionCleanupDocument candidate : due) {
            SubscriptionCleanupDocument claimed = claim(candidate.getId());
            if (claimed != null) {
                run(claimed);
            }
        }
    }

    /** Takes a due deletion for this replica, moving its next try forward; {@code null} if another took it. */
    private SubscriptionCleanupDocument claim(String id) {
        Instant now = clock.instant();
        Query query = new Query(Criteria.where("_id").is(id).and("notBefore").lte(now));
        Update update = new Update().set("notBefore", now.plus(retryDelay)).inc("attempts", 1);
        return mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true),
                SubscriptionCleanupDocument.class);
    }

    void run(SubscriptionCleanupDocument cleanup) {
        List<BridgeDocument> users = bridges.findBySourceTopicAndSourceSubscription(cleanup.getTopic(),
                cleanup.getSubscription());
        if (!users.isEmpty()) {
            LOG.info("Subscription {} on {} kept: bridge '{}' uses it", cleanup.getSubscription(), cleanup.getTopic(),
                    users.get(0).getName());
            repository.deleteById(cleanup.getId());
            return;
        }
        Long backlog = backlog(cleanup);
        try {
            admin.topics().deleteSubscription(cleanup.getTopic(), cleanup.getSubscription());
        } catch (PulsarAdminException.NotFoundException e) {
            LOG.info("Subscription {} on {} already gone", cleanup.getSubscription(), cleanup.getTopic());
            repository.deleteById(cleanup.getId());
            return;
        } catch (PulsarAdminException | RuntimeException e) {
            LOG.warn("Subscription {} on {} not deleted yet (attempt {}), will retry: {}", cleanup.getSubscription(),
                    cleanup.getTopic(), cleanup.getAttempts(), e.getMessage());
            mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(cleanup.getId())),
                    new Update().set("lastError", e.getMessage()), SubscriptionCleanupDocument.class);
            return;
        }
        repository.deleteById(cleanup.getId());
        LOG.info("Subscription {} on {} deleted (bridge '{}')", cleanup.getSubscription(), cleanup.getTopic(),
                cleanup.getBridgeName());
        audit.subscriptionDeleted(cleanup, backlog);
    }

    private Long backlog(SubscriptionCleanupDocument cleanup) {
        try {
            TopicStats stats = admin.topics().getStats(cleanup.getTopic());
            SubscriptionStats subscription = stats.getSubscriptions().get(cleanup.getSubscription());
            return subscription == null ? null : subscription.getMsgBacklog();
        } catch (PulsarAdminException | RuntimeException e) {
            return null;
        }
    }
}
