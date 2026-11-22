package com.catacumbas69.catasonarr.dto;

import com.catacumbas69.catasonarr.model.AddOptions;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.Ratings;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Sonarr's {@code SeriesResource}, field for field (Sonarr 4.0.20), so Overseerr, Jellyseerr, Bazarr and Homarr read
 * Catasonarr as Sonarr (orch.md decision 112). In a request, only what the user sets is read.
 *
 * @param id the id; 0 in a lookup of a series not in the library
 * @param title the title
 * @param alternateTitles other titles
 * @param sortTitle the sort title
 * @param status {@code continuing}, {@code ended}, {@code upcoming}
 * @param ended whether it ended
 * @param profileName {@code null}
 * @param overview the summary
 * @param nextAiring next monitored episode's air time
 * @param previousAiring last monitored episode that aired
 * @param network the network
 * @param airTime local air time
 * @param images poster, fanart
 * @param originalLanguage original language
 * @param remotePoster the poster's address
 * @param seasons seasons
 * @param year first year
 * @param path the series' folder
 * @param qualityProfileId the quality profile
 * @param seasonFolder episodes in season folders
 * @param monitored whether it is monitored
 * @param monitorNewItems {@code all} or {@code none}
 * @param useSceneNumbering scene numbering
 * @param runtime minutes per episode
 * @param tvdbId TheTVDB's id
 * @param tvRageId TVRage's id
 * @param tvMazeId TVmaze's id
 * @param tmdbId TMDB's id
 * @param firstAired first episode's date
 * @param lastAired last episode's date
 * @param seriesType {@code standard}, {@code daily}, {@code anime}
 * @param cleanTitle the clean title
 * @param imdbId IMDb's id
 * @param titleSlug the slug
 * @param rootFolderPath its root folder
 * @param folder the folder's name
 * @param certification age rating
 * @param genres genres
 * @param tags tag ids
 * @param added when it was added
 * @param addOptions what to do when added (requests only)
 * @param ratings rating
 * @param statistics statistics
 * @param episodesChanged {@code null}
 * @param languageProfileId always 1: Sonarr v4 has no language profiles
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeriesResource(
        int id,
        String title,
        List<AlternateTitleResource> alternateTitles,
        String sortTitle,
        String status,
        boolean ended,
        String profileName,
        String overview,
        Instant nextAiring,
        Instant previousAiring,
        String network,
        String airTime,
        List<MediaCoverResource> images,
        Language originalLanguage,
        String remotePoster,
        List<SeasonResource> seasons,
        int year,
        String path,
        int qualityProfileId,
        boolean seasonFolder,
        boolean monitored,
        String monitorNewItems,
        boolean useSceneNumbering,
        int runtime,
        int tvdbId,
        int tvRageId,
        int tvMazeId,
        int tmdbId,
        Instant firstAired,
        Instant lastAired,
        String seriesType,
        String cleanTitle,
        String imdbId,
        String titleSlug,
        String rootFolderPath,
        String folder,
        String certification,
        List<String> genres,
        Set<Integer> tags,
        Instant added,
        AddOptions addOptions,
        Ratings ratings,
        SeriesStatisticsResource statistics,
        Boolean episodesChanged,
        int languageProfileId) {
}
