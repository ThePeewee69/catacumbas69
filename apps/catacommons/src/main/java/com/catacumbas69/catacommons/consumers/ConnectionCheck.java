package com.catacumbas69.catacommons.consumers;

import java.util.Map;

/**
 * The last connection check of a replica.
 *
 * @param checkedAt when (ISO-8601)
 * @param checkedBy who asked: a browser address, or the app for an automatic check
 * @param results result per dependency, e.g. {@code mongodb}, {@code pulsar}
 */
public record ConnectionCheck(String checkedAt, String checkedBy, Map<String, ProbeResult> results) {

    /**
     * Tells whether every dependency answered.
     *
     * @return {@code true} when all are up
     */
    public boolean allUp() {
        return results.values().stream().allMatch(ProbeResult::up);
    }
}
