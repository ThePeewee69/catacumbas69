package com.catacumbas69.passario.service;

import java.time.Instant;
import java.util.List;

public record BridgeWorkerStatus(
        String bridgeId,
        String name,
        boolean running,
        long messagesForwarded,
        long errors,
        Instant startedAt,
        String sourceTopic,
        List<String> destinationTopics
) {}
