package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.parser.LanguageParser;
import com.catacumbas69.catasonarr.parser.ParsedEpisodeInfo;
import com.catacumbas69.catasonarr.parser.Parser;
import com.catacumbas69.catasonarr.parser.QualityParser;
import com.catacumbas69.catasonarr.parser.SeriesTitleInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Matches a parsed release to a series and its episodes, translated from Sonarr's {@code ParsingService}
 * (GPL-3.0, orch.md decision 122). Without Sonarr's scene mappings (its own service): series by clean title,
 * TVmaze's alternate titles, title and year, then the ids the indexer gives; episodes by season and number, air date,
 * absolute number, or a special's title.
 */
@Service
public class ReleaseMatcher {

    private final Library library;

    /**
     * Creates the matcher.
     *
     * @param library the series and episodes
     */
    public ReleaseMatcher(Library library) {
        this.library = library;
    }

    /**
     * Matches a parsed release.
     *
     * @param parsed what its name says
     * @param tvdbId TheTVDB's id the indexer gives, or 0
     * @param imdbId IMDb's id the indexer gives, or {@code null}
     * @param criteria the search, or {@code null}
     * @return the release with its series (or none) and episodes
     */
    public RemoteEpisode map(ParsedEpisodeInfo parsed, int tvdbId, String imdbId, SearchCriteria criteria) {
        RemoteEpisode remote = new RemoteEpisode();
        remote.parsed = parsed;
        Series series = findSeries(parsed, tvdbId, imdbId, criteria);
        if (series != null) {
            remote.series = series;
            remote.profile = library.profile(series);
            if (!(parsed.isDaily() && "standard".equals(series.seriesType()))) {
                remote.episodes = episodes(parsed, series, criteria);
            }
        }
        remote.languages = parsed.languages;
        if (criteria != null) {
            List<Integer> wanted = criteria.episodes().stream().map(Episode::id).toList();
            remote.episodeRequested = remote.episodes.stream().anyMatch(e -> wanted.contains(e.id()));
        }
        return remote;
    }

    /**
     * Matches a release to a known series and episodes (a download Catasonarr grabbed).
     *
     * @param parsed what its name says
     * @param seriesId the series
     * @param episodeIds the episodes
     * @return the release with its series and episodes
     */
    public RemoteEpisode map(ParsedEpisodeInfo parsed, int seriesId, List<Integer> episodeIds) {
        RemoteEpisode remote = new RemoteEpisode();
        remote.parsed = parsed;
        remote.series = library.series(seriesId).orElse(null);
        remote.profile = library.profile(remote.series);
        remote.episodes = new ArrayList<>(library.episodes(episodeIds));
        remote.languages = parsed.languages;
        return remote;
    }

    /**
     * Parses a release's title, falling back to a special's title in its series as Sonarr's
     * {@code ParseSpecialEpisodeTitle}.
     *
     * @param release the release
     * @param criteria the search, or {@code null}
     * @return what it says, or {@code null}
     */
    public ParsedEpisodeInfo parse(ReleaseInfo release, SearchCriteria criteria) {
        ParsedEpisodeInfo parsed = Parser.parseTitle(release.title());
        if (parsed == null || parsed.isPossibleSpecialEpisode()) {
            ParsedEpisodeInfo special = special(parsed, release, criteria);
            if (special != null) {
                return special;
            }
        }
        return parsed;
    }

    private ParsedEpisodeInfo special(ParsedEpisodeInfo parsed, ReleaseInfo release, SearchCriteria criteria) {
        Series series = null;
        if (criteria != null && release.tvdbId() != 0 && release.tvdbId() == criteria.series().tvdbId()) {
            series = criteria.series();
        }
        if (series == null && parsed != null && parsed.seriesTitle != null) {
            series = library.byTitle(parsed.seriesTitle).orElse(null);
        }
        if (series == null && release.tvdbId() > 0) {
            series = library.byTvdbId(release.tvdbId()).orElse(null);
        }
        if (series == null && release.imdbId() != null) {
            series = library.byImdbId(release.imdbId()).orElse(null);
        }
        if (series == null) {
            return null;
        }
        Optional<Episode> episode = library.specialByTitle(series.id(), release.title());
        if (episode.isEmpty()) {
            return null;
        }
        ParsedEpisodeInfo info = new ParsedEpisodeInfo();
        info.releaseTitle = release.title();
        info.seriesTitle = series.title();
        info.seriesTitleInfo = new SeriesTitleInfo();
        info.seriesTitleInfo.title = series.title();
        info.seasonNumber = episode.get().seasonNumber();
        info.episodeNumbers = new int[] {episode.get().episodeNumber()};
        info.quality = QualityParser.parseQuality(release.title());
        info.releaseGroup = Parser.parseReleaseGroup(release.title());
        info.languages = LanguageParser.parseLanguages(release.title());
        info.special = true;
        return info;
    }

    private Series findSeries(ParsedEpisodeInfo parsed, int tvdbId, String imdbId, SearchCriteria criteria) {
        if (criteria != null) {
            Series wanted = criteria.series();
            if (Parser.cleanSeriesTitle(wanted.title()).equals(Parser.cleanSeriesTitle(parsed.seriesTitle))) {
                return wanted;
            }
            if (tvdbId > 0 && tvdbId == wanted.tvdbId()) {
                return wanted;
            }
            if (imdbId != null && imdbId.equals(wanted.imdbId()) && tvdbId <= 0) {
                return wanted;
            }
        }
        Optional<Series> series = library.byTitle(parsed.seriesTitle);
        if (series.isEmpty() && parsed.seriesTitleInfo != null && parsed.seriesTitleInfo.allTitles != null) {
            series = byAllTitles(parsed.seriesTitleInfo.allTitles);
        }
        if (series.isEmpty() && parsed.seriesTitleInfo != null && parsed.seriesTitleInfo.year > 0) {
            series = library.byTitle(parsed.seriesTitleInfo.titleWithoutYear, parsed.seriesTitleInfo.year);
        }
        if (series.isEmpty() && tvdbId > 0) {
            series = library.byTvdbId(tvdbId);
        }
        if (series.isEmpty() && imdbId != null && !imdbId.isBlank() && tvdbId <= 0) {
            series = library.byImdbId(imdbId);
        }
        return series.orElse(null);
    }

    /** Every title must point at the same series, as Sonarr's {@code GetSeriesByAllTitles}. */
    private Optional<Series> byAllTitles(String[] titles) {
        Series found = null;
        for (String title : titles) {
            Optional<Series> s = library.byTitle(title);
            if (s.isEmpty()) {
                continue;
            }
            if (found != null && found.id() != s.get().id()) {
                return Optional.empty();
            }
            found = s.get();
        }
        return Optional.ofNullable(found);
    }

    /**
     * The episodes a parsed name means in a known series (an imported file's).
     *
     * @param parsed the parsed name
     * @param series the series
     * @return its episodes, possibly none
     */
    public List<Episode> episodesFor(ParsedEpisodeInfo parsed, Series series) {
        return episodes(parsed, series, null);
    }

    private List<Episode> episodes(ParsedEpisodeInfo parsed, Series series, SearchCriteria criteria) {
        List<Episode> result = new ArrayList<>();
        if (parsed.fullSeason) {
            return new ArrayList<>(library.season(series.id(), parsed.seasonNumber));
        }
        if (parsed.isDaily()) {
            Optional<Episode> e = criteria == null ? Optional.empty()
                    : criteria.episodes().stream().filter(x -> parsed.airDate.equals(x.airDate())).findFirst();
            if (e.isEmpty()) {
                e = library.episodeByAirDate(series.id(), parsed.airDate, parsed.dailyPart);
            }
            e.ifPresent(result::add);
            return result;
        }
        if (parsed.isAbsoluteNumbering()) {
            for (int absolute : parsed.absoluteEpisodeNumbers) {
                Optional<Episode> e = parsed.special ? library.episode(series.id(), 0, absolute)
                        : library.episodeByAbsolute(series.id(), absolute);
                if (e.isEmpty() && parsed.seasonNumber > 1 && parsed.episodeNumbers.length == 0) {
                    e = library.episode(series.id(), parsed.seasonNumber, absolute);
                }
                e.ifPresent(result::add);
            }
            return result;
        }
        if (parsed.isPossibleSceneSeasonSpecial()) {
            Optional<Episode> special = library.specialByTitle(series.id(), parsed.releaseTitle);
            if (special.isPresent()) {
                return List.of(special.get());
            }
        }
        if (parsed.special && parsed.seasonNumber != 0) {
            return result;
        }
        for (int number : parsed.episodeNumbers) {
            Optional<Episode> e = criteria == null ? Optional.empty() : criteria.episodes().stream()
                    .filter(x -> x.seasonNumber() == parsed.seasonNumber && x.episodeNumber() == number).findFirst();
            if (e.isEmpty()) {
                e = library.episode(series.id(), parsed.seasonNumber, number);
            }
            e.ifPresent(result::add);
        }
        return result;
    }
}
