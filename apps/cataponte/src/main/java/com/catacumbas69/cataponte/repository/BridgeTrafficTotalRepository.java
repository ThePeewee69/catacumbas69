package com.catacumbas69.cataponte.repository;

import com.catacumbas69.cataponte.model.BridgeTrafficTotal;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Read access to {@code bridge_traffic_totals}. Writes go through {@code TrafficWriter} bulk upserts.
 */
public interface BridgeTrafficTotalRepository extends MongoRepository<BridgeTrafficTotal, String> {

    /**
     * Returns the totals of one bridge.
     *
     * @param bridgeId bridge id
     * @return one entry per role and topic
     */
    List<BridgeTrafficTotal> findByBridgeIdOrderByRoleAscTopicAsc(String bridgeId);
}
