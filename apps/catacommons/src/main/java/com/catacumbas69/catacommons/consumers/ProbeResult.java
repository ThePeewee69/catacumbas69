package com.catacumbas69.catacommons.consumers;

/**
 * Result of one connection check.
 *
 * @param up whether the dependency answered
 * @param millis how long the check took
 * @param error why it failed, or {@code null}
 */
public record ProbeResult(boolean up, long millis, String error) {

    /**
     * A dependency that answered.
     *
     * @param millis how long it took
     * @return the result
     */
    public static ProbeResult up(long millis) {
        return new ProbeResult(true, millis, null);
    }

    /**
     * A dependency that did not answer.
     *
     * @param millis how long the check took
     * @param error why
     * @return the result
     */
    public static ProbeResult down(long millis, String error) {
        return new ProbeResult(false, millis, error);
    }
}
