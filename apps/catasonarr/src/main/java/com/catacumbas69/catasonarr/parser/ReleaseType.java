package com.catacumbas69.catasonarr.parser;

/** What a release holds (Sonarr's {@code ReleaseType}); the numbers are Sonarr's. */
public enum ReleaseType {
    /** Not known. */
    UNKNOWN(0),
    /** One episode. */
    SINGLE_EPISODE(1),
    /** Several episodes. */
    MULTI_EPISODE(2),
    /** A whole season. */
    SEASON_PACK(3);

    private final int value;

    ReleaseType(int value) {
        this.value = value;
    }

    /**
     * Sonarr's number for it.
     *
     * @return the number
     */
    public int value() {
        return value;
    }
}
