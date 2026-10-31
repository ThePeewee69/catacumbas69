package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.model.Schema;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Lease in MongoDB that lets several Catametricas replicas run while only one collects (ADR 0007).
 *
 * <p>The lease is one document ({@code _id} {@value Schema#LOCK_ID}) with an owner and an expiry. An
 * instance takes it when it is free or expired, and renews it on every collection; if the owner dies,
 * another instance takes over once the lease expires (three intervals). The upsert is atomic: when the
 * lease is held by someone else the insert hits the {@code _id} and fails with a duplicate key.
 */
@Component
public class CollectorLock {

    private static final Logger LOG = LoggerFactory.getLogger(CollectorLock.class);
    private static final String OWNER = "owner";
    private static final String EXPIRES_AT = "expiresAt";

    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final String owner;

    /**
     * Creates the lock.
     *
     * @param mongoTemplate template for the lease document
     * @param clock time source
     */
    public CollectorLock(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.owner = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Takes or renews the lease.
     *
     * @param lease how long the lease lasts without renewal
     * @return {@code true} if this instance holds the lease and may collect
     */
    public boolean tryAcquire(Duration lease) {
        Instant now = clock.instant();
        Query freeOrMine = Query.query(Criteria.where("_id").is(Schema.LOCK_ID).orOperator(
                Criteria.where(EXPIRES_AT).lt(now),
                Criteria.where(OWNER).is(owner)));
        Update take = Update.update(OWNER, owner).set(EXPIRES_AT, now.plus(lease)).set("renewedAt", now);
        try {
            mongoTemplate.upsert(freeOrMine, take, Schema.LOCKS);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Gives the lease up on shutdown so another replica can take over at once. */
    @PreDestroy
    public void release() {
        try {
            mongoTemplate.remove(Query.query(Criteria.where("_id").is(Schema.LOCK_ID).and(OWNER).is(owner)),
                    Schema.LOCKS);
        } catch (RuntimeException e) {
            LOG.debug("Lease not released ({}); it expires on its own", e.getMessage());
        }
    }

    /**
     * Returns this instance's identity in the lease.
     *
     * @return host name plus a random suffix
     */
    public String owner() {
        return owner;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "catametricas";
        }
    }
}
