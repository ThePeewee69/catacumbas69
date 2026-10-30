package com.catacumbas69.passario.repository;

import com.catacumbas69.passario.document.BridgeDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BridgeRepository extends MongoRepository<BridgeDocument, String> {
    List<BridgeDocument> findByEnabled(boolean enabled);
}
