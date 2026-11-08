package com.catacumbas69.catavigia.model;

import java.time.Instant;

/**
 * One check of one round, published on {@code catacumbas69/catavigia/check-tasks} (orch.md decision 75). Every
 * replica publishes the same ids, and the first replica to claim a task runs it.
 *
 * @param id {@code <check>@<round start>}; also the message key
 * @param check check name, e.g. {@code backlog}
 * @param round start of the round (ISO-8601)
 * @param intervalSeconds round length when it was published
 */
public record CheckTask(String id, String check, String round, int intervalSeconds) {

    /**
     * Creates the task of a check and round.
     *
     * @param check check name
     * @param round start of the round
     * @param intervalSeconds round length
     * @return the task
     */
    public static CheckTask of(String check, Instant round, int intervalSeconds) {
        return new CheckTask(check + "@" + round, check, round.toString(), intervalSeconds);
    }

    /**
     * Returns the start of the round.
     *
     * @return the instant, or {@code null} if {@code round} is not ISO-8601
     */
    public Instant roundStart() {
        try {
            return round == null ? null : Instant.parse(round);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Tells whether the task has what a replica needs to run it.
     *
     * @return {@code true} if id, check and a valid round are present
     */
    public boolean complete() {
        return id != null && !id.isBlank() && check != null && !check.isBlank() && roundStart() != null;
    }
}
