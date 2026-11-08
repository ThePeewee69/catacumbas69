package com.catacumbas69.catavigia.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The debounce count of one {@code <type>|<subject>}, stored in {@value Schema#OBSERVATIONS} (orch.md decision 75).
 * Consecutive rounds can run on different replicas, so the count lives in MongoDB. It is moved forward only by a
 * round newer than {@code lastRound}, so a task delivered late changes nothing.
 *
 * @param key {@code <type>|<subject>} ({@code _id})
 * @param type alert type
 * @param subject what it is about
 * @param check check that reports it
 * @param seen rounds in a row that saw the problem
 * @param clear rounds in a row that did not
 * @param lastRound start of the last round counted
 */
@Document(collection = Schema.OBSERVATIONS)
public record Observation(
        @Id String key,
        String type,
        String subject,
        String check,
        int seen,
        int clear,
        Instant lastRound) {
}
