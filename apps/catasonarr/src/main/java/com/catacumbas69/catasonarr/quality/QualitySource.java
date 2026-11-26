package com.catacumbas69.catasonarr.quality;

/**
 * Where a quality comes from, in Sonarr's order (translated from Sonarr 4.0.20's {@code QualitySource.cs}, GPL-3.0):
 * the order matters, as Sonarr picks the nearest source at or above one it cannot match exactly.
 */
public enum QualitySource {
    /** Unknown. */
    UNKNOWN("unknown"),
    /** Broadcast television. */
    TELEVISION("television"),
    /** Raw broadcast (Raw-HD). */
    TELEVISION_RAW("televisionRaw"),
    /** A web download. */
    WEB("web"),
    /** A web rip. */
    WEB_RIP("webRip"),
    /** DVD. */
    DVD("dvd"),
    /** Blu-ray. */
    BLURAY("bluray"),
    /** A Blu-ray remux. */
    BLURAY_RAW("blurayRaw");

    private final String apiName;

    QualitySource(String apiName) {
        this.apiName = apiName;
    }

    /**
     * The name in Sonarr's API, e.g. {@code webRip}.
     *
     * @return the name
     */
    public String apiName() {
        return apiName;
    }

    /**
     * The source of a quality.
     *
     * @param quality the quality
     * @return its source
     */
    public static QualitySource of(Quality quality) {
        for (QualitySource s : values()) {
            if (s.apiName.equals(quality.source())) {
                return s;
            }
        }
        return UNKNOWN;
    }
}
