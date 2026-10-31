package com.catacumbas69.cataponte.dto;

import com.catacumbas69.cataponte.service.BridgeWorkerStatus;
import java.util.List;

/**
 * Body of {@code GET /api/v1/bridges/status}.
 *
 * @param activeCount bridges currently relaying messages
 * @param workers status of every running, starting or failed bridge
 */
public record BridgeStatusResponse(int activeCount, List<BridgeWorkerStatus> workers) {
}
