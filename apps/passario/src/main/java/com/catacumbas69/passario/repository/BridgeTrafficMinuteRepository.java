package com.catacumbas69.passario.repository;

import com.catacumbas69.passario.model.BridgeTrafficMinute;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Read access to {@code bridge_traffic_minutes}. Writes go through {@code TrafficWriter} bulk upserts.
 */
public interface BridgeTrafficMinuteRepository extends MongoRepository<BridgeTrafficMinute, String> {

    /**
     * Returns the per-minute counts of one bridge in a time range, oldest first.
     *
     * @param bridgeId bridge id
     * @param from first minute, inclusive
     * @param to last minute, exclusive
     * @return the minutes with traffic
     */
    List<BridgeTrafficMinute> findByBridgeIdAndMinuteGreaterThanEqualAndMinuteLessThanOrderByMinuteAsc(
            String bridgeId, Instant from, Instant to);
}
