package com.catacumbas69.catametricas.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Outcome of the last collection, stored in {@value Schema#STATUS} with {@code _id}
 * {@value Schema#STATUS_ID}. Catapulsar shows it as "updated N s ago".
 *
 * @param id always {@value Schema#STATUS_ID}
 * @param finishedAt when the last collection finished
 * @param durationMillis how long it took
 * @param topics topics read successfully
 * @param failedTopics topics whose statistics could not be read
 * @param intervalSeconds interval in use
 * @param owner instance that ran it
 */
@Document(collection = Schema.STATUS)
public record CollectorStatus(
        @Id String id,
        Instant finishedAt,
        long durationMillis,
        int topics,
        int failedTopics,
        int intervalSeconds,
        String owner) {
}
