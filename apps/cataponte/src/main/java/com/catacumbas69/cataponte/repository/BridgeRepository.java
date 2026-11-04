package com.catacumbas69.cataponte.repository;

import com.catacumbas69.cataponte.model.BridgeDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Access to the {@code cataponte_bridges} collection.
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

    /**
     * Finds a bridge by its unique name.
     *
     * @param name bridge name
     * @return the bridge, if any
     */
    Optional<BridgeDocument> findByName(String name);

    /**
     * Finds the bridges of an area.
     *
     * @param areaId area id
     * @return its bridges
     */
    List<BridgeDocument> findByAreaId(String areaId);

    /**
     * Finds the bridges that read a topic with a subscription.
     *
     * @param topic source topic
     * @param subscription subscription name
     * @return those bridges
     */
    List<BridgeDocument> findBySourceTopicAndSourceSubscription(String topic, String subscription);
}
