package com.catacumbas69.catavigia.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The claim of one check task, stored in {@value Schema#TASK_RUNS} (ADR 0012): inserting it is the claim, and a
 * duplicate key means another replica has the task. The result is filled when the check is done. A TTL index
 * removes runs after {@code catavigia.tasks.run-retention}.
 *
 * @param id task id ({@code _id})
 * @param check check name
 * @param round start of the round (ISO-8601)
 * @param claimedAt when the task was claimed
 * @param instance replica that claimed it
 * @param finishedAt when it was done, or {@code null} while running
 * @param durationMillis how long it took
 * @param findings problems seen
 */
@Document(collection = Schema.TASK_RUNS)
public record TaskRun(
        @Id String id,
        String check,
        String round,
        Instant claimedAt,
        String instance,
        Instant finishedAt,
        Long durationMillis,
        Integer findings) {

    /**
     * Creates the claim of a task.
     *
     * @param task the task
     * @param claimedAt now
     * @param instance this replica
     * @return the run, without result
     */
    public static TaskRun claim(CheckTask task, Instant claimedAt, String instance) {
        return new TaskRun(task.id(), task.check(), task.round(), claimedAt, instance, null, null, null);
    }
}
