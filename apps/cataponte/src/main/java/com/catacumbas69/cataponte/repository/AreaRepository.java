package com.catacumbas69.cataponte.repository;

import com.catacumbas69.cataponte.model.AreaDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Access to the {@code cataponte_areas} collection.
 */
public interface AreaRepository extends MongoRepository<AreaDocument, String> {

    /**
     * Checks whether an area with the given name exists.
     *
     * @param name area name
     * @return {@code true} if one exists
     */
    boolean existsByName(String name);

    /**
     * Checks whether an area other than {@code id} uses the given name.
     *
     * @param name area name
     * @param id id to exclude
     * @return {@code true} if another area uses the name
     */
    boolean existsByNameAndIdNot(String name, String id);
}
