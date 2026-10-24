package com.catacumbas69.pulsarclient.repository;

import com.catacumbas69.pulsarclient.model.AuditEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AuditRepository extends MongoRepository<AuditEntry, String> {

    List<AuditEntry> findByTopicOrderByTimestampDesc(String topic);

    List<AuditEntry> findByActionOrderByTimestampDesc(String action);

    Page<AuditEntry> findAllByOrderByTimestampDesc(Pageable pageable);

    List<AuditEntry> findByTimestampBetweenOrderByTimestampDesc(Instant from, Instant to);
}
