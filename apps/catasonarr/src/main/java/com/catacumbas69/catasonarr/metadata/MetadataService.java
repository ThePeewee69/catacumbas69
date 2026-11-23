package com.catacumbas69.catasonarr.metadata;

import com.catacumbas69.catasonarr.config.CatasonarrProperties;
import com.catacumbas69.catasonarr.model.MediaCover;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Where series and episodes come from (orch.md decision 112): TVmaze, completed by TMDB with the images and the
 * summary in the configured language. TMDB failing never stops a series from being added or refreshed: TVmaze's
 * poster and English summary stay.
 */
@Service
public class MetadataService {

    private static final Logger LOG = LoggerFactory.getLogger(MetadataService.class);

    private final TvMazeClient tvmaze;
    private final TmdbClient tmdb;
    private final String language;
    private final String imageUrl;

    /**
     * Creates the service.
     *
     * @param tvmaze TVmaze
     * @param tmdb TMDB
     * @param properties language and TMDB's image address
     */
    public MetadataService(TvMazeClient tvmaze, TmdbClient tmdb, CatasonarrProperties properties) {
        this.tvmaze = tvmaze;
        this.tmdb = tmdb;
        this.language = properties.metadata().language();
        this.imageUrl = properties.metadata().tmdbImageUrl().replaceAll("/+$", "");
    }

    /**
     * Sonarr's {@code series/lookup}: {@code tvdb:<id>} and {@code imdb:<id>} find one series, with its episodes (so
     * its seasons) and TMDB's images; anything else is a search by name, without episodes. A folder's name works too
     * (the library import, decision 115): an id in it ({@code {tvdb-79168}}) finds that series, and a year at its end
     * ({@code The Office (2005)}) puts the series of that year first. Series TVmaze has no TheTVDB id for are left
     * out: Sonarr's clients add series by that id.
     *
     * @param term what was typed
     * @return the series found
     */
    public List<SeriesInfo> lookup(String term) {
        String t = term == null ? "" : term.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tvdb:") || lower.startsWith("tvdbid:")) {
            Integer id = parseInt(t.substring(t.indexOf(':') + 1).strip());
            return id == null ? List.of() : byTvdb(id).map(List::of).orElse(List.of());
        }
        if (lower.startsWith("imdb:")) {
            return tvmaze.lookupImdb(t.substring(5).strip()).map(this::full).filter(s -> s.tvdbId() > 0)
                    .map(List::of).orElse(List.of());
        }
        FolderTerm folder = FolderTerm.parse(t);
        if (folder.tvdbId() != null) {
            return byTvdb(folder.tvdbId()).map(List::of).orElse(List.of());
        }
        if (folder.imdbId() != null) {
            return tvmaze.lookupImdb(folder.imdbId()).map(this::full).filter(s -> s.tvdbId() > 0)
                    .map(List::of).orElse(List.of());
        }
        if (folder.title().isEmpty()) {
            return List.of();
        }
        List<SeriesInfo> found = new ArrayList<>();
        for (JsonNode show : tvmaze.search(folder.title())) {
            SeriesInfo info = TvMazeMapper.show(show);
            if (info.tvdbId() > 0) {
                found.add(info);
            }
        }
        if (folder.year() != null) {
            int year = folder.year();
            found.sort(Comparator.comparing(s -> s.year() != year)); // the year asked first, TVmaze's order kept
        }
        return found;
    }

    /**
     * Reads a series by TheTVDB's id, with its episodes and TMDB's images and summary.
     *
     * @param tvdbId TheTVDB's id
     * @return the series, or empty when TVmaze does not know it
     */
    public Optional<SeriesInfo> byTvdb(int tvdbId) {
        return tvmaze.lookupTvdb(tvdbId).map(this::full);
    }

    /**
     * Reads a series again, for a refresh, by TVmaze's id.
     *
     * @param tvMazeId TVmaze's id
     * @param knownTmdbId TMDB's id if known, else 0
     * @return the series, or empty when TVmaze no longer has it
     */
    public Optional<SeriesInfo> byTvMaze(int tvMazeId, int knownTmdbId) {
        return tvmaze.show(tvMazeId).map(show -> full(show, knownTmdbId));
    }

    private SeriesInfo full(JsonNode show) {
        return full(show, 0);
    }

    private SeriesInfo full(JsonNode show, int knownTmdbId) {
        SeriesInfo info = TvMazeMapper.show(show);
        info = info.withEpisodes(TvMazeMapper.episodes(tvmaze.episodes(info.tvMazeId())),
                TvMazeMapper.akas(tvmaze.akas(info.tvMazeId())));
        return withTmdb(info, knownTmdbId);
    }

    private SeriesInfo withTmdb(SeriesInfo info, int knownTmdbId) {
        if (!tmdb.enabled()) {
            return info;
        }
        try {
            int tmdbId = knownTmdbId > 0 ? knownTmdbId : tmdb.findByTvdb(info.tvdbId()).orElse(0);
            Optional<JsonNode> tv = tmdb.tv(tmdbId, language);
            if (tv.isEmpty()) {
                return info;
            }
            String overview = TvMazeMapper.plain(tv.get().path("overview").asText(null));
            List<MediaCover> images = new ArrayList<>();
            String poster = tv.get().path("poster_path").asText(null);
            images.add(poster != null && !poster.isBlank() ? new MediaCover("poster", imageUrl + poster)
                    : info.images().stream().filter(i -> "poster".equals(i.coverType())).findFirst().orElse(null));
            String backdrop = tv.get().path("backdrop_path").asText(null);
            if (backdrop != null && !backdrop.isBlank()) {
                images.add(new MediaCover("fanart", imageUrl + backdrop));
            }
            images.removeIf(Objects::isNull);
            return info.withTmdb(tmdbId, overview != null ? overview : info.overview(), images);
        } catch (MetadataUnavailableException e) {
            LOG.warn("TMDB not read for {} (tvdb {}), TVmaze's images and summary kept: {}", info.title(),
                    info.tvdbId(), e.getMessage());
            return info;
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
