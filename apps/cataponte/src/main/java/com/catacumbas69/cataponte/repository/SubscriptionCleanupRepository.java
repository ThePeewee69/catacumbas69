package com.catacumbas69.cataponte.repository;

import com.catacumbas69.cataponte.model.SubscriptionCleanupDocument;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Access to the {@code cataponte_subscription_cleanups} collection.
 */
public interface SubscriptionCleanupRepository extends MongoRepository<SubscriptionCleanupDocument, String> {

    /**
     * Finds the deletions that may be tried now.
     *
     * @param now current time
     * @return the due deletions
     */
    List<SubscriptionCleanupDocument> findByNotBeforeLessThanEqual(Instant now);
}
