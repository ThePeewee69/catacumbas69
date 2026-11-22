package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Sonarr's {@code EpisodeResource}. No episode has a file until the import exists.
 *
 * @param id the id
 * @param seriesId the series
 * @param tvdbId TheTVDB's episode id: 0, TVmaze does not have it
 * @param episodeFileId 0
 * @param seasonNumber the season
 * @param episodeNumber the number
 * @param title the title
 * @param airDate local air date
 * @param airDateUtc air time in UTC
 * @param lastSearchTime {@code null}
 * @param runtime minutes
 * @param finaleType {@code null}
 * @param overview the summary
 * @param hasFile {@code false}
 * @param monitored whether it is monitored
 * @param absoluteEpisodeNumber the number across seasons
 * @param sceneAbsoluteEpisodeNumber {@code null}
 * @param sceneEpisodeNumber {@code null}
 * @param sceneSeasonNumber {@code null}
 * @param unverifiedSceneNumbering {@code false}
 * @param endTime air time plus runtime
 * @param grabDate {@code null}
 * @param series the series, when asked for
 * @param images its still
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EpisodeResource(int id, int seriesId, int tvdbId, int episodeFileId, int seasonNumber,
                              int episodeNumber, String title, String airDate, Instant airDateUtc,
                              Instant lastSearchTime, int runtime, String finaleType, String overview, boolean hasFile,
                              boolean monitored, Integer absoluteEpisodeNumber, Integer sceneAbsoluteEpisodeNumber,
                              Integer sceneEpisodeNumber, Integer sceneSeasonNumber, boolean unverifiedSceneNumbering,
                              Instant endTime, Instant grabDate, SeriesResource series,
                              List<MediaCoverResource> images) {
}
