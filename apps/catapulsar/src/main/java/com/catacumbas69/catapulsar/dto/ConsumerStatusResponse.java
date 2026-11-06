package com.catacumbas69.catapulsar.dto;

import com.catacumbas69.catacommons.consumers.ConnectionCheck;
import com.catacumbas69.catacommons.consumers.ConsumerStatus;

/**
 * One consumer of one replica of an app, as read from {@code catacumbas69/<app>/consumers} (ADR 0015).
 *
 * @param app app name
 * @param instance replica
 * @param consumer consumer name inside the app
 * @param topic topic consumed
 * @param subscription subscription
 * @param state {@code STARTING}, {@code RUNNING}, {@code PAUSED_AUTO} or {@code PAUSED_MANUAL}
 * @param reason why it is paused, or {@code null}
 * @param changedBy who made the last change
 * @param since when the state began
 * @param updatedAt when the replica last reported
 * @param deadLetterTopic its DLQ
 * @param maxDeliveries deliveries before the DLQ
 * @param lastCheck the replica's last connection check, or {@code null}
 * @param stale {@code true} when the replica has not reported for a while (stopped or unreachable)
 */
public record ConsumerStatusResponse(String app, String instance, String consumer, String topic,
                                     String subscription, String state, String reason, String changedBy,
                                     String since, String updatedAt, String deadLetterTopic, int maxDeliveries,
                                     ConnectionCheck lastCheck, boolean stale) {

    /**
     * Converts a status read from the topic.
     *
     * @param status the status
     * @param stale whether it is too old
     * @return the response
     */
    public static ConsumerStatusResponse of(ConsumerStatus status, boolean stale) {
        return new ConsumerStatusResponse(status.app(), status.instance(), status.consumer(), status.topic(),
                status.subscription(), status.state(), status.reason(), status.changedBy(), status.since(),
                status.updatedAt(), status.deadLetterTopic(), status.maxDeliveries(), status.lastCheck(), stale);
    }
}
