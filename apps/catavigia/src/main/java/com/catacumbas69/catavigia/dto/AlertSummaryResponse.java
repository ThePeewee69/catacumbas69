package com.catacumbas69.catavigia.dto;

/**
 * What Catapulsar's badge shows (ADR 0015 section 4): open alerts not marked as seen, by severity.
 *
 * @param open every open alert
 * @param unseen open alerts not marked as seen
 * @param critical unseen critical alerts
 * @param warning unseen warning alerts
 * @param worst {@code critical}, {@code warning}, or {@code null} when nothing is unseen
 * @param stored {@code false} while MongoDB is down: only this replica's {@code mongodb.down} is counted
 */
public record AlertSummaryResponse(int open, int unseen, int critical, int warning, String worst, boolean stored) {
}
