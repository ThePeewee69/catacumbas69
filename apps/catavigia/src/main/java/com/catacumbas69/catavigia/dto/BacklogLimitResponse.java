package com.catacumbas69.catavigia.dto;

import com.catacumbas69.catavigia.model.BacklogLimit;
import java.time.Instant;

/**
 * A subscription's own backlog limit.
 *
 * @param topic topic without partition suffix
 * @param subscription subscription name
 * @param limit messages above which {@code backlog} opens
 * @param updatedBy who set it
 * @param updatedAt when
 */
public record BacklogLimitResponse(String topic, String subscription, long limit, String updatedBy,
                                   Instant updatedAt) {

    /**
     * Describes a stored limit.
     *
     * @param limit the limit
     * @return the response
     */
    public static BacklogLimitResponse of(BacklogLimit limit) {
        return new BacklogLimitResponse(limit.topic(), limit.subscription(), limit.limit(), limit.updatedBy(),
                limit.updatedAt());
    }
}
