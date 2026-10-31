package com.catacumbas69.catapulsar.repository;

import com.catacumbas69.catapulsar.model.AuditEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Access to the {@code audit_logs} collection.
 */
public interface AuditRepository extends MongoRepository<AuditEntry, String> {

    /**
     * Returns audit entries, newest first.
     *
     * @param pageable page request
     * @return one page of entries
     */
    Page<AuditEntry> findAllByOrderByTimestampDesc(Pageable pageable);
}
