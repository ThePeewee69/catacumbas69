package com.catacumbas69.cataponte.repository;

import com.catacumbas69.cataponte.model.BridgeDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Access to the {@code bridges} collection.
 */
public interface BridgeRepository extends MongoRepository<BridgeDocument, String> {

    /**
     * Checks whether a bridge with the given name exists.
     *
     * @param name bridge name
     * @return {@code true} if one exists
     */
    boolean existsByName(String name);

    /**
     * Checks whether a bridge other than {@code id} uses the given name.
     *
     * @param name bridge name
     * @param id id to exclude
     * @return {@code true} if another bridge uses the name
     */
    boolean existsByNameAndIdNot(String name, String id);
}
