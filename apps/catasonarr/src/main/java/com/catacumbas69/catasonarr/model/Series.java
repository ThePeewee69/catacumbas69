package com.catacumbas69.catasonarr.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A series in the library (orch.md decision 112), with Sonarr's integer id so the clients that keep it (Overseerr,
 * Jellyseerr, Bazarr) work unchanged. Metadata from TVmaze and TMDB; the rest is the user's.
 *
 * @param id Sonarr-style id, from {@code catasonarr_sequences}
 * @param tvdbId TheTVDB's id, the one Sonarr's clients use
 * @param tvMazeId TVmaze's id, where the metadata comes from
 * @param tmdbId TMDB's id, or 0
 * @param tvRageId TVRage's id, or 0
 * @param imdbId IMDb's id, or {@code null}
 * @param title the title
 * @param sortTitle the title for sorting (lower case, without a leading article)
 * @param cleanTitle the title in lower-case letters and digits only
 * @param titleSlug the title in an address
 * @param status {@code continuing}, {@code ended} or {@code upcoming}
 * @param overview the summary, in the configured language when TMDB has it
 * @param network the network or streaming service
 * @param airTime local air time, {@code HH:mm}, or {@code null}
 * @param runtime minutes per episode
 * @param year year of the first episode, or 0
 * @param firstAired first episode's date
 * @param lastAired last episode's date, when ended
 * @param genres genres
 * @param certification age rating, or {@code null}
 * @param ratings rating
 * @param originalLanguage original language
 * @param images poster and fanart
 * @param alternateTitles other titles
 * @param seasons seasons and whether each is monitored
 * @param path the series' folder, e.g. {@code /data/TV/Andor}
 * @param qualityProfileId its quality profile
 * @param seasonFolder whether episodes go into season folders
 * @param monitored whether the series is monitored
 * @param monitorNewItems {@code all} (new seasons are monitored) or {@code none}
 * @param useSceneNumbering whether scene numbering is used
 * @param seriesType {@code standard}, {@code daily} or {@code anime}
 * @param tags tag ids
 * @param added when it was added
 * @param lastInfoSync when its metadata was last refreshed
 * @param addOptions what was asked when it was added
 */
@Document("catasonarr_series")
public record Series(
        @Id int id,
        int tvdbId,
        int tvMazeId,
        int tmdbId,
        int tvRageId,
        String imdbId,
        String title,
        String sortTitle,
        String cleanTitle,
        String titleSlug,
        String status,
        String overview,
        String network,
        String airTime,
        int runtime,
        int year,
        Instant firstAired,
        Instant lastAired,
        List<String> genres,
        String certification,
        Ratings ratings,
        Language originalLanguage,
        List<MediaCover> images,
        List<AlternateTitle> alternateTitles,
        List<Season> seasons,
        String path,
        int qualityProfileId,
        boolean seasonFolder,
        boolean monitored,
        String monitorNewItems,
        boolean useSceneNumbering,
        String seriesType,
        Set<Integer> tags,
        Instant added,
        Instant lastInfoSync,
        AddOptions addOptions) {
}
