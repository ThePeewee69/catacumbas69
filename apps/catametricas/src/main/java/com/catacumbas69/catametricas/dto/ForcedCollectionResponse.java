package com.catacumbas69.catametricas.dto;

import java.time.Instant;
import java.util.List;

/**
 * Result of a forced collection.
 *
 * @param scope {@code cluster}, a tenant, or {@code tenant/namespace}
 * @param namespaces the namespaces of the scope
 * @param collected namespaces collected by this request
 * @param coalesced namespaces already collected (or being collected) by another request in the same window
 * @param failed namespaces that could not be collected; the next scheduled collection covers them
 * @param finishedAt when the request finished
 * @param durationMillis how long it took
 */
public record ForcedCollectionResponse(String scope, List<String> namespaces, int collected, int coalesced,
                                       List<String> failed, Instant finishedAt, long durationMillis) {
}
