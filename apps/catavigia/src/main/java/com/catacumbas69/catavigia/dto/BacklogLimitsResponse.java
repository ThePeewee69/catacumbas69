package com.catacumbas69.catavigia.dto;

import java.util.List;

/**
 * The global default and every subscription's own limit.
 *
 * @param globalLimit {@code alerts.backlog-limit}, for subscriptions without their own
 * @param limits the subscriptions' own limits
 */
public record BacklogLimitsResponse(long globalLimit, List<BacklogLimitResponse> limits) {
}
