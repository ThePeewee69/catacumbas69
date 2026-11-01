package com.catacumbas69.cataponte.dto;

import java.util.List;

/**
 * Body of {@code GET /api/v1/bridges/status}: the status of every bridge over all replicas (ADR 0012).
 *
 * @param activeCount bridges relaying messages in at least one replica
 * @param workers status of every running, starting, failed or blocked bridge
 */
public record BridgeStatusResponse(int activeCount, List<BridgeRuntimeStatus> workers) {
}
