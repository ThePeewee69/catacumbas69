package com.catacumbas69.catasonarr.metadata;

import com.catacumbas69.catasonarr.model.MediaCover;
import java.time.Instant;
import java.util.List;

/**
 * An episode as the metadata source describes it (orch.md decision 112).
 *
 * @param tvMazeId TVmaze's id
 * @param seasonNumber the season, 0 for specials
 * @param episodeNumber the number within the season
 * @param absoluteEpisodeNumber the number across the regular seasons, or {@code null} for specials
 * @param title the title
 * @param overview the summary, plain text
 * @param airDate local air date, {@code yyyy-MM-dd}, or {@code null}
 * @param airDateUtc air time in UTC, or {@code null}
 * @param runtime minutes, or 0
 * @param images its still, if any
 */
public record EpisodeInfo(
        int tvMazeId,
        int seasonNumber,
        int episodeNumber,
        Integer absoluteEpisodeNumber,
        String title,
        String overview,
        String airDate,
        Instant airDateUtc,
        int runtime,
        List<MediaCover> images) {
}
