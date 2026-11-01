package com.catacumbas69.catametricas.dto;

import java.time.Instant;

/**
 * State of the collection, for "updated N s ago".
 *
 * @param lastCollectionAt most recent sample of any topic, or {@code null} if nothing was ever collected
 * @param intervalSeconds collection interval in use
 */
public record CollectorResponse(Instant lastCollectionAt, int intervalSeconds) {
}
