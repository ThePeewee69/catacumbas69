package com.catacumbas69.catasonarr.decision;

/**
 * Why a release is not grabbed (Sonarr's {@code DownloadRejection}).
 *
 * @param reason Sonarr's reason, e.g. {@code qualityNotWanted}
 * @param message as a person reads it
 * @param temporary whether it may be grabbed later (a delay, the minimum age)
 */
public record Rejection(String reason, String message, boolean temporary) {

    /**
     * A permanent rejection.
     *
     * @param reason the reason
     * @param message the message
     * @return the rejection
     */
    public static Rejection of(String reason, String message) {
        return new Rejection(reason, message, false);
    }
}
