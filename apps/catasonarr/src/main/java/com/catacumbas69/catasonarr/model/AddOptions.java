package com.catacumbas69.catasonarr.model;

/**
 * Sonarr's {@code addOptions}: what to do when a series is added.
 *
 * @param ignoreEpisodesWithFiles leave the episodes with a file unmonitored
 * @param ignoreEpisodesWithoutFiles leave the episodes without a file unmonitored
 * @param monitor which episodes to monitor: {@code all}, {@code future}, {@code missing}, {@code existing},
 *                {@code firstSeason}, {@code lastSeason}, {@code latestSeason}, {@code pilot}, {@code recent},
 *                {@code monitorSpecials}, {@code unmonitorSpecials}, {@code none}, {@code skip} or {@code unknown}
 *                (then each season's own flag decides)
 * @param searchForMissingEpisodes search for the missing episodes right away
 * @param searchForCutoffUnmetEpisodes search for better releases right away
 */
public record AddOptions(
        boolean ignoreEpisodesWithFiles,
        boolean ignoreEpisodesWithoutFiles,
        String monitor,
        boolean searchForMissingEpisodes,
        boolean searchForCutoffUnmetEpisodes) {
}
