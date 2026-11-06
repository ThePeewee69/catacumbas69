package com.catacumbas69.cataponte.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What one Cataponte replica reports about one of its bridges ({@value #COLLECTION}), written every few seconds so
 * any replica can answer {@code GET /api/v1/bridges/status} for all of them (ADR 0012). Reports older than
 * {@code cataponte.status.stale-after} are ignored; a TTL index removes those of replicas that died.
 *
 * @param id {@code <bridgeId>@<instance>}
 * @param bridgeId bridge id
 * @param instance replica that reports
 * @param name bridge name
 * @param state lifecycle state in that replica
 * @param running {@code true} when the worker is relaying
 * @param active {@code true} when that replica's consumer receives messages
 * @param messagesForwarded messages that replica relayed since its worker started
 * @param errors failed deliveries in that replica
 * @param inFlight messages that replica sent but not yet confirmed
 * @param startedAt when that replica's worker started relaying
 * @param sourceTopic source topic
 * @param destinationTopics destination topics
 * @param ordered whether the bridge runs in ordered mode
 * @param lastError last error in that replica
 * @param nextRetryAt next start attempt in that replica after a failure
 * @param updatedAt when the report was written
 * @param problem {@code DESTINATION_UNAVAILABLE} or {@code STUCK} in that replica, or {@code null}
 * @param stuckMessageId message an ordered bridge is stuck on in that replica, or {@code null}
 * @param deadLetterTopic the bridge's DLQ, or {@code null}
 */
@Document(collection = WorkerStatusDocument.COLLECTION)
public record WorkerStatusDocument(
        @Id String id,
        String bridgeId,
        String instance,
        String name,
        String state,
        boolean running,
        boolean active,
        long messagesForwarded,
        long errors,
        int inFlight,
        Instant startedAt,
        String sourceTopic,
        List<String> destinationTopics,
        boolean ordered,
        String lastError,
        Instant nextRetryAt,
        Instant updatedAt,
        String problem,
        String stuckMessageId,
        String deadLetterTopic) {

    /** Collection name. */
    public static final String COLLECTION = "cataponte_worker_status";

    /**
     * Returns the id of a replica's report on a bridge.
     *
     * @param bridgeId bridge id
     * @param instance replica
     * @return {@code <bridgeId>@<instance>}
     */
    public static String idOf(String bridgeId, String instance) {
        return bridgeId + "@" + instance;
    }
}
