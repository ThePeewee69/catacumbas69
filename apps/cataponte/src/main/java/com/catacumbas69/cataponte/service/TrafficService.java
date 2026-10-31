package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.BridgeTrafficMinute;
import com.catacumbas69.cataponte.model.BridgeTrafficTotal;
import com.catacumbas69.cataponte.repository.BridgeTrafficMinuteRepository;
import com.catacumbas69.cataponte.repository.BridgeTrafficTotalRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Reads the traffic counts stored by {@link TrafficRecorder}. Counts reach MongoDB in batches, so the
 * newest few seconds may not be visible yet.
 */
@Service
public class TrafficService {

    /** Longest time range one per-minute query may cover (PERF-5). */
    public static final Duration MAX_RANGE = Duration.ofDays(7);

    private final BridgeTrafficTotalRepository totals;
    private final BridgeTrafficMinuteRepository minutes;

    /**
     * Creates the service.
     *
     * @param totals all-time counts
     * @param minutes per-minute counts
     */
    public TrafficService(BridgeTrafficTotalRepository totals, BridgeTrafficMinuteRepository minutes) {
        this.totals = totals;
        this.minutes = minutes;
    }

    /**
     * Returns the all-time counts of every bridge, including deleted ones.
     *
     * @return one entry per bridge, role and topic
     */
    public List<BridgeTrafficTotal> allTotals() {
        return totals.findAll(Sort.by("bridgeName", "role", "topic"));
    }

    /**
     * Returns the all-time counts of one bridge.
     *
     * @param bridgeId bridge id
     * @return one entry per role and topic
     */
    public List<BridgeTrafficTotal> totals(String bridgeId) {
        return totals.findByBridgeIdOrderByRoleAscTopicAsc(bridgeId);
    }

    /**
     * Returns the per-minute counts of one bridge.
     *
     * @param bridgeId bridge id
     * @param from first minute, inclusive
     * @param to end of the range, exclusive
     * @return the minutes with traffic, oldest first
     * @throws BridgeValidationException if the range is empty, reversed or longer than {@link #MAX_RANGE}
     */
    public List<BridgeTrafficMinute> minutes(String bridgeId, Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new BridgeValidationException("from", "must be before 'to'");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BridgeValidationException("to", "the range may cover at most " + MAX_RANGE.toDays() + " days");
        }
        return minutes.findByBridgeIdAndMinuteGreaterThanEqualAndMinuteLessThanOrderByMinuteAsc(bridgeId, from, to);
    }
}
