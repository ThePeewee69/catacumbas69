package com.catacumbas69.catasonarr.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/**
 * Sonarr's statistics of a season or a series (orch.md decision 112). Until files are imported none has a file.
 *
 * @param seasonNumber the season, or -1 for a whole series
 * @param totalEpisodeCount every episode
 * @param episodeCount the monitored episodes that have aired (or have a file), as Sonarr counts them
 * @param episodeFileCount episodes with a file
 * @param sizeOnDisk bytes of their files
 * @param nextAiring the next monitored episode's air time, or {@code null}
 * @param previousAiring the last monitored episode that aired, or {@code null}
 */
public record Statistics(int seasonNumber, int totalEpisodeCount, int episodeCount, int episodeFileCount,
                         long sizeOnDisk, Instant nextAiring, Instant previousAiring) {

    /**
     * Statistics of a season with no episodes.
     *
     * @param seasonNumber the season
     * @return zeros
     */
    public static Statistics empty(int seasonNumber) {
        return new Statistics(seasonNumber, 0, 0, 0, 0, null, null);
    }

    /**
     * Sonarr's percentage: files over the episodes it counts.
     *
     * @return 0 to 100
     */
    public double percentOfEpisodes() {
        return episodeCount == 0 ? 0 : episodeFileCount * 100.0 / episodeCount;
    }

    /**
     * Adds up a series' seasons.
     *
     * @param seasons the seasons' statistics
     * @return the series' statistics
     */
    public static Statistics sum(Collection<Statistics> seasons) {
        return new Statistics(-1,
                seasons.stream().mapToInt(Statistics::totalEpisodeCount).sum(),
                seasons.stream().mapToInt(Statistics::episodeCount).sum(),
                seasons.stream().mapToInt(Statistics::episodeFileCount).sum(),
                seasons.stream().mapToLong(Statistics::sizeOnDisk).sum(),
                seasons.stream().map(Statistics::nextAiring).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null),
                seasons.stream().map(Statistics::previousAiring).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null));
    }
}
