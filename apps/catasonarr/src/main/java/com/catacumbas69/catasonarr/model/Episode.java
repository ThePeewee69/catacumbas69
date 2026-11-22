package com.catacumbas69.catasonarr.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An episode of a series in the library (orch.md decision 112). Numbered as TVmaze numbers it, which is the order the
 * episodes aired in; TVmaze's specials go to season 0, numbered by air date.
 *
 * @param id Sonarr-style id
 * @param seriesId the series
 * @param tvMazeId TVmaze's id, which keeps the episode the same across refreshes
 * @param seasonNumber the season, 0 for specials
 * @param episodeNumber the number within the season
 * @param absoluteEpisodeNumber the number across the regular seasons, or {@code null} for specials
 * @param title the title
 * @param overview the summary
 * @param airDate local air date, {@code yyyy-MM-dd}, or {@code null}
 * @param airDateUtc air time in UTC, or {@code null}
 * @param runtime minutes
 * @param monitored whether it is wanted
 * @param images its still, if any
 */
@Document("catasonarr_episodes")
public record Episode(
        @Id int id,
        int seriesId,
        int tvMazeId,
        int seasonNumber,
        int episodeNumber,
        Integer absoluteEpisodeNumber,
        String title,
        String overview,
        String airDate,
        Instant airDateUtc,
        int runtime,
        boolean monitored,
        List<MediaCover> images) {
}
