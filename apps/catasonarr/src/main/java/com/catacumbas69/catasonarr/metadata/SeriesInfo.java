package com.catacumbas69.catasonarr.metadata;

import com.catacumbas69.catasonarr.model.AlternateTitle;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.MediaCover;
import com.catacumbas69.catasonarr.model.Ratings;
import java.time.Instant;
import java.util.List;

/**
 * A series as the metadata sources describe it: TVmaze, completed by TMDB (orch.md decision 112).
 *
 * @param tvdbId TheTVDB's id, from TVmaze's external ids; 0 when TVmaze does not know it
 * @param tvMazeId TVmaze's id
 * @param tmdbId TMDB's id, or 0
 * @param tvRageId TVRage's id, or 0
 * @param imdbId IMDb's id, or {@code null}
 * @param title the title
 * @param status {@code continuing}, {@code ended} or {@code upcoming}
 * @param overview the summary, plain text
 * @param network the network or streaming service, or {@code null}
 * @param airTime local air time, {@code HH:mm}, or {@code null}
 * @param runtime minutes per episode, or 0
 * @param year year of the first episode, or 0
 * @param firstAired first episode's date, or {@code null}
 * @param lastAired last episode's date when ended, or {@code null}
 * @param genres genres
 * @param ratings rating
 * @param originalLanguage original language
 * @param images poster, fanart
 * @param alternateTitles other titles
 * @param episodes every episode with the specials, or empty when only the series was asked for (a search)
 */
public record SeriesInfo(
        int tvdbId,
        int tvMazeId,
        int tmdbId,
        int tvRageId,
        String imdbId,
        String title,
        String status,
        String overview,
        String network,
        String airTime,
        int runtime,
        int year,
        Instant firstAired,
        Instant lastAired,
        List<String> genres,
        Ratings ratings,
        Language originalLanguage,
        List<MediaCover> images,
        List<AlternateTitle> alternateTitles,
        List<EpisodeInfo> episodes) {

    /**
     * Returns this series with other values from TMDB.
     *
     * @param newTmdbId TMDB's id
     * @param newOverview the summary
     * @param newImages the images
     * @return the copy
     */
    public SeriesInfo withTmdb(int newTmdbId, String newOverview, List<MediaCover> newImages) {
        return new SeriesInfo(tvdbId, tvMazeId, newTmdbId, tvRageId, imdbId, title, status, newOverview, network,
                airTime, runtime, year, firstAired, lastAired, genres, ratings, originalLanguage, newImages,
                alternateTitles, episodes);
    }

    /**
     * Returns this series with its episodes and other titles.
     *
     * @param newEpisodes the episodes
     * @param newAlternateTitles the other titles
     * @return the copy
     */
    public SeriesInfo withEpisodes(List<EpisodeInfo> newEpisodes, List<AlternateTitle> newAlternateTitles) {
        return new SeriesInfo(tvdbId, tvMazeId, tmdbId, tvRageId, imdbId, title, status, overview, network, airTime,
                runtime, year, firstAired, lastAired, genres, ratings, originalLanguage, images, newAlternateTitles,
                newEpisodes);
    }
}
