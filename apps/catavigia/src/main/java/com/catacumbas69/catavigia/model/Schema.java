package com.catacumbas69.catavigia.model;

/**
 * Names of the MongoDB collections and fields Catavigia owns (ADR 0010). Nobody else reads them: other services
 * use Catavigia's API ({@code /api/v1/alerts}, {@code /api/v1/limits}).
 *
 * <ul>
 *   <li>{@value #ALERTS}: open alerts, unique {@value #KEY} ({@code <type>|<subject>}).</li>
 *   <li>{@value #ARCHIVE}: closed alerts, kept for ever (orch.md decision 72).</li>
 *   <li>{@value #OBSERVATIONS}: the debounce counts per {@code <type>|<subject>} (orch.md decision 75).</li>
 *   <li>{@value #TASK_RUNS}: claims of check tasks; removed after the run retention (TTL index).</li>
 *   <li>{@value #BACKLOG_LIMITS}: backlog limits set per subscription (orch.md decision 70).</li>
 * </ul>
 */
public final class Schema {

    /** Open alerts. */
    public static final String ALERTS = "catavigia_alerts";
    /** Closed alerts. */
    public static final String ARCHIVE = "catavigia_alerts_archive";
    /** Debounce counts. */
    public static final String OBSERVATIONS = "catavigia_observations";
    /** Claims of check tasks. */
    public static final String TASK_RUNS = "catavigia_task_runs";
    /** Backlog limits per subscription. */
    public static final String BACKLOG_LIMITS = "catavigia_backlog_limits";

    /** {@code <type>|<subject>}: one open alert per key. */
    public static final String KEY = "key";
    /** Alert type, e.g. {@code backlog}. */
    public static final String TYPE = "type";
    /** What the alert is about, e.g. a bridge name. */
    public static final String SUBJECT = "subject";
    /** Check that reports the type (observations). */
    public static final String CHECK = "check";
    /** When an alert opened. */
    public static final String OPENED_AT = "openedAt";
    /** When an alert closed (archive). */
    public static final String CLOSED_AT = "closedAt";
    /** Last round that saw the problem (alerts). */
    public static final String LAST_SEEN_AT = "lastSeenAt";
    /** Details of the last round that saw the problem (alerts). */
    public static final String DETAILS = "details";
    /** Who marked the alert as seen. */
    public static final String SEEN_BY = "seenBy";
    /** When the alert was marked as seen. */
    public static final String SEEN_AT = "seenAt";
    /** Rounds in a row that saw the problem (observations). */
    public static final String SEEN = "seen";
    /** Rounds in a row that did not see it (observations). */
    public static final String CLEAR = "clear";
    /** Start of the last round counted (observations). */
    public static final String LAST_ROUND = "lastRound";
    /** When a task was claimed (task runs; TTL). */
    public static final String CLAIMED_AT = "claimedAt";

    private Schema() {
    }

    /**
     * Returns the key of a type and subject.
     *
     * @param type alert type
     * @param subject what it is about
     * @return {@code <type>|<subject>}
     */
    public static String key(String type, String subject) {
        return type + "|" + subject;
    }
}
