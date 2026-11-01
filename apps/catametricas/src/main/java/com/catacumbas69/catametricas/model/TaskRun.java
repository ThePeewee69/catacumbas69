package com.catacumbas69.catametricas.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The claim of one collection task, stored in {@value Schema#TASK_RUNS} (ADR 0012). Inserting it is the claim:
 * a duplicate key means another instance already has the task. The result fields are filled when the task is
 * done. A TTL index removes runs after {@code catametricas.tasks.run-retention}.
 *
 * @param id task id ({@code _id})
 * @param namespace full namespace
 * @param bucket start of the task's interval (ISO-8601)
 * @param claimedAt when the task was claimed
 * @param instance instance that claimed it
 * @param finishedAt when it was done, or {@code null} while running
 * @param durationMillis how long it took
 * @param topics topics read
 * @param failedTopics topics whose statistics could not be read
 * @param advancedTopics topics whose snapshot this run moved forward (and whose traffic it counted)
 */
@Document(collection = Schema.TASK_RUNS)
public record TaskRun(
        @Id String id,
        String namespace,
        String bucket,
        Instant claimedAt,
        String instance,
        Instant finishedAt,
        Long durationMillis,
        Integer topics,
        Integer failedTopics,
        Integer advancedTopics) {

    /**
     * Creates the claim of a task.
     *
     * @param task the task
     * @param claimedAt now
     * @param instance this instance
     * @return the run, without result
     */
    public static TaskRun claim(CollectTask task, Instant claimedAt, String instance) {
        return new TaskRun(task.id(), task.namespace(), task.bucket(), claimedAt, instance, null, null, null, null,
                null);
    }
}
