package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.model.AlternateTitle;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.parser.Parser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The series and episodes as the decision engine looks them up (Sonarr's {@code FindByTitle} and the episode
 * finders): by clean title (Sonarr's {@code CleanSeriesTitle}), alternate titles, year and ids; episodes by season
 * and number, air date, absolute number or title. Read straight from MongoDB, without events.
 */
@Service
public class Library {

    private final MongoTemplate mongo;

    /**
     * Creates the lookup.
     *
     * @param mongo MongoDB
     */
    public Library(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Every series.
     *
     * @return the series
     */
    public List<Series> allSeries() {
        return mongo.findAll(Series.class);
    }

    /**
     * A series.
     *
     * @param id the id
     * @return the series, if any
     */
    public Optional<Series> series(int id) {
        return Optional.ofNullable(mongo.findById(id, Series.class));
    }

    /**
     * A series' quality profile.
     *
     * @param series the series
     * @return the profile, or {@code null}
     */
    public QualityProfile profile(Series series) {
        return series == null ? null : mongo.findById(series.qualityProfileId(), QualityProfile.class);
    }

    /**
     * A series by title, as Sonarr's {@code FindByTitle}: its clean title or one of its alternate titles.
     *
     * @param title the title in a release
     * @return the series, if exactly one fits
     */
    public Optional<Series> byTitle(String title) {
        String clean = Parser.cleanSeriesTitle(title);
        List<Series> all = allSeries();
        List<Series> matches = all.stream().filter(s -> Parser.cleanSeriesTitle(s.title()).equals(clean)).toList();
        if (matches.isEmpty()) {
            matches = all.stream().filter(s -> alternates(s).contains(clean)).toList();
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /**
     * A series by title and year, as Sonarr's {@code FindByTitle(title, year)}.
     *
     * @param title the title without the year
     * @param year the year
     * @return the series, if exactly one fits
     */
    public Optional<Series> byTitle(String title, int year) {
        String clean = Parser.cleanSeriesTitle(title);
        List<Series> matches = allSeries().stream().filter(s -> s.year() == year
                && (Parser.cleanSeriesTitle(s.title()).equals(clean) || alternates(s).contains(clean))).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /**
     * A series by TheTVDB's id.
     *
     * @param tvdbId the id
     * @return the series, if any
     */
    public Optional<Series> byTvdbId(int tvdbId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("tvdbId").is(tvdbId)), Series.class));
    }

    /**
     * A series by IMDb's id.
     *
     * @param imdbId the id
     * @return the series, if any
     */
    public Optional<Series> byImdbId(String imdbId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("imdbId").is(imdbId)), Series.class));
    }

    /**
     * A series' episodes.
     *
     * @param seriesId the series
     * @return its episodes by season and number
     */
    public List<Episode> episodes(int seriesId) {
        return mongo.find(Query.query(Criteria.where("seriesId").is(seriesId))
                .with(Sort.by("seasonNumber", "episodeNumber")), Episode.class);
    }

    /**
     * Episodes by id.
     *
     * @param ids the ids
     * @return the episodes found
     */
    public List<Episode> episodes(Collection<Integer> ids) {
        return mongo.find(Query.query(Criteria.where("id").in(ids)).with(Sort.by("seasonNumber", "episodeNumber")),
                Episode.class);
    }

    /**
     * A season's episodes.
     *
     * @param seriesId the series
     * @param seasonNumber the season
     * @return the episodes
     */
    public List<Episode> season(int seriesId, int seasonNumber) {
        return episodes(seriesId).stream().filter(e -> e.seasonNumber() == seasonNumber).toList();
    }

    /**
     * An episode by season and number.
     *
     * @param seriesId the series
     * @param seasonNumber the season
     * @param episodeNumber the number
     * @return the episode, if any
     */
    public Optional<Episode> episode(int seriesId, int seasonNumber, int episodeNumber) {
        return episodes(seriesId).stream()
                .filter(e -> e.seasonNumber() == seasonNumber && e.episodeNumber() == episodeNumber).findFirst();
    }

    /**
     * An episode by absolute number.
     *
     * @param seriesId the series
     * @param absolute the absolute number
     * @return the episode, if exactly one
     */
    public Optional<Episode> episodeByAbsolute(int seriesId, int absolute) {
        List<Episode> found = episodes(seriesId).stream()
                .filter(e -> Objects.equals(e.absoluteEpisodeNumber(), absolute)).toList();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * A daily episode by air date and part, as Sonarr's {@code FindEpisode(seriesId, airDate, part)}: the only one
     * that day, or the part-th of the day's episodes by number.
     *
     * @param seriesId the series
     * @param airDate {@code yyyy-MM-dd}
     * @param part the part, or {@code null}
     * @return the episode, if any
     */
    public Optional<Episode> episodeByAirDate(int seriesId, String airDate, Integer part) {
        List<Episode> day = episodes(seriesId).stream().filter(e -> airDate.equals(e.airDate())).toList();
        if (day.size() == 1) {
            return Optional.of(day.get(0));
        }
        if (part != null && part > 0 && part <= day.size()) {
            return Optional.of(day.get(part - 1));
        }
        return Optional.empty();
    }

    /**
     * A special by its title in a release (Sonarr's {@code FindEpisodeByTitle} in season 0): the longest special
     * title the release contains.
     *
     * @param seriesId the series
     * @param releaseTitle the release's title
     * @return the special, if any
     */
    public Optional<Episode> specialByTitle(int seriesId, String releaseTitle) {
        String normalized = Parser.normalizeEpisodeTitle(releaseTitle).replace(".", " ");
        return season(seriesId, 0).stream().filter(e -> e.title() != null && !e.title().isBlank())
                .filter(e -> {
                    String t = Parser.normalizeEpisodeTitle(e.title());
                    return !t.isBlank() && normalized.contains(t);
                })
                .max((a, b) -> Integer.compare(a.title().length(), b.title().length()));
    }

    private static List<String> alternates(Series s) {
        List<String> all = new ArrayList<>();
        if (s.alternateTitles() != null) {
            for (AlternateTitle a : s.alternateTitles()) {
                all.add(Parser.cleanSeriesTitle(a.title()));
            }
        }
        all.add(Parser.cleanSeriesTitle(s.title().toLowerCase(Locale.ROOT).replaceAll("\\s*\\(\\d{4}\\)$", "")));
        return all;
    }
}
