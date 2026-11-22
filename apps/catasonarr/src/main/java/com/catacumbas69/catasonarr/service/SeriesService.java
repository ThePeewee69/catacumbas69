package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.metadata.EpisodeInfo;
import com.catacumbas69.catasonarr.metadata.MetadataService;
import com.catacumbas69.catasonarr.metadata.SeriesInfo;
import com.catacumbas69.catasonarr.model.AddOptions;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.RootFolder;
import com.catacumbas69.catasonarr.model.Season;
import com.catacumbas69.catasonarr.model.Series;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The series in the library (orch.md decision 112): add, change, refresh and delete, as Sonarr does them.
 */
@Service
public class SeriesService {

    /** Sequence name. */
    public static final String SEQUENCE = "series";

    private static final Logger LOG = LoggerFactory.getLogger(SeriesService.class);

    /**
     * What is asked when a series is added: Sonarr's {@code POST /api/v3/series}.
     *
     * @param tvdbId TheTVDB's id
     * @param qualityProfileId the quality profile
     * @param path the series' folder, or {@code null} to make it from the root folder and the naming
     * @param rootFolderPath the root folder, when there is no path
     * @param seasonFolder episodes in season folders
     * @param monitored whether the series is monitored
     * @param monitorNewItems {@code all} or {@code none}; {@code null} is {@code all}
     * @param seriesType {@code standard}, {@code daily} or {@code anime}; {@code null} is {@code standard}
     * @param tags tag ids
     * @param seasons the seasons asked to be monitored, or empty
     * @param addOptions what to monitor and whether to search
     */
    public record AddRequest(int tvdbId, int qualityProfileId, String path, String rootFolderPath,
                             boolean seasonFolder, boolean monitored, String monitorNewItems, String seriesType,
                             Set<Integer> tags, List<Season> seasons, AddOptions addOptions) {
    }

    /**
     * What a change may set: Sonarr's {@code PUT /api/v3/series}. Metadata comes from the sources and is not changed
     * here.
     *
     * @param qualityProfileId the quality profile
     * @param path the series' folder
     * @param seasonFolder episodes in season folders
     * @param monitored whether the series is monitored
     * @param monitorNewItems {@code all} or {@code none}
     * @param seriesType {@code standard}, {@code daily} or {@code anime}
     * @param useSceneNumbering scene numbering
     * @param tags tag ids
     * @param seasons the seasons and whether each is monitored; {@code null} keeps them
     */
    public record Change(int qualityProfileId, String path, boolean seasonFolder, boolean monitored,
                         String monitorNewItems, String seriesType, boolean useSceneNumbering, Set<Integer> tags,
                         List<Season> seasons) {
    }

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final MetadataService metadata;
    private final EpisodeService episodes;
    private final QualityService quality;
    private final RootFolderService rootFolders;
    private final NamingService naming;
    private final CatasonarrEvents events;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param metadata TVmaze and TMDB
     * @param episodes episodes
     * @param quality quality profiles
     * @param rootFolders root folders
     * @param naming folder naming
     * @param events events
     * @param clock the time
     */
    public SeriesService(MongoTemplate mongo, Sequences sequences, MetadataService metadata, EpisodeService episodes,
                         QualityService quality, RootFolderService rootFolders, NamingService naming,
                         CatasonarrEvents events, Clock clock) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.metadata = metadata;
        this.episodes = episodes;
        this.quality = quality;
        this.rootFolders = rootFolders;
        this.naming = naming;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Returns every series, by sort title.
     *
     * @return the series
     */
    public List<Series> all() {
        return mongo.find(new Query().with(Sort.by("sortTitle")), Series.class);
    }

    /**
     * Returns a series.
     *
     * @param id the id
     * @return the series
     */
    public Series get(int id) {
        Series series = mongo.findById(id, Series.class);
        if (series == null) {
            throw new NotFoundException("Series " + id);
        }
        return series;
    }

    /**
     * Finds a series by TheTVDB's id.
     *
     * @param tvdbId TheTVDB's id
     * @return the series, or empty
     */
    public Optional<Series> byTvdb(int tvdbId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("tvdbId").is(tvdbId)), Series.class));
    }

    /**
     * The folder a series would get in a root folder, from the naming.
     *
     * @param info the series
     * @return the folder name
     */
    public String folderName(SeriesInfo info) {
        return FolderNames.seriesFolder(naming.get(), new FolderNames.Names(info.title(), info.year(), info.tvdbId(),
                info.tvMazeId(), info.tmdbId(), info.imdbId()));
    }

    /**
     * Adds a series, as Sonarr: reads its metadata, makes its path, creates its episodes and sets what is monitored.
     * No folder is created: that happens when the first episode is imported.
     *
     * @param request what was asked
     * @param actor who did it
     * @return the series
     */
    public Series add(AddRequest request, String actor) {
        if (request.tvdbId() <= 0) {
            throw new BadRequestException("tvdbId", "'Tvdb Id' must be greater than '0'.");
        }
        if (byTvdb(request.tvdbId()).isPresent()) {
            throw new BadRequestException("tvdbId", "This series has already been added");
        }
        if (!quality.profileExists(request.qualityProfileId())) {
            throw new BadRequestException("qualityProfileId", "QualityProfile does not exist");
        }
        SeriesInfo info = metadata.byTvdb(request.tvdbId()).orElseThrow(() -> new BadRequestException("tvdbId",
                "A series with this ID was not found (TVmaze does not know TheTVDB id " + request.tvdbId() + ")"));
        String path = path(request, info);
        if (mongo.exists(Query.query(Criteria.where("path").is(path)), Series.class)) {
            throw new BadRequestException("path", "Path is already configured for an existing series");
        }
        Instant now = clock.instant();
        int id = sequences.next(SEQUENCE);
        List<Season> seasons = seasons(info, request.seasons());
        AddOptions options = request.addOptions() == null
                ? new AddOptions(false, false, "unknown", false, false) : request.addOptions();
        Series series = fromInfo(id, info, null, slug(info, id), path, request.qualityProfileId(),
                request.seasonFolder(), request.monitored(), orDefault(request.monitorNewItems(), "all"),
                false, orDefault(request.seriesType(), "standard"),
                request.tags() == null ? Set.of() : request.tags(), now, now, options, seasons);
        List<Episode> created = newEpisodes(id, info.episodes(), seasons);
        MonitorRules.Result monitoring = MonitorRules.apply(options.monitor(), options.ignoreEpisodesWithoutFiles(),
                series.status(), created, seasons, now);
        series = withSeasons(series, monitoring.seasons());
        mongo.insert(series);
        if (!monitoring.episodes().isEmpty()) {
            mongo.insert(monitoring.episodes(), Episode.class);
        }
        events.change("series", "added", subject(series), actor, null, describe(series));
        LOG.info("Series added: {} ({} episodes, {} monitored) by {}", subject(series), monitoring.episodes().size(),
                monitoring.episodes().stream().filter(Episode::monitored).count(), actor);
        return series;
    }

    /**
     * Changes what the user sets on a series. A season's monitored flag, when it changes, is applied to its
     * episodes, as Sonarr does.
     *
     * @param id the series
     * @param change the new values
     * @param actor who did it
     * @return the series
     */
    public Series update(int id, Change change, String actor) {
        Series before = get(id);
        if (!quality.profileExists(change.qualityProfileId())) {
            throw new BadRequestException("qualityProfileId", "QualityProfile does not exist");
        }
        String path = change.path() == null || change.path().isBlank() ? before.path()
                : RootFolderService.normalize(change.path());
        if (!path.equals(before.path())
                && mongo.exists(Query.query(Criteria.where("path").is(path).and("id").ne(id)), Series.class)) {
            throw new BadRequestException("path", "Path is already configured for an existing series");
        }
        List<Season> seasons = change.seasons() == null || change.seasons().isEmpty() ? before.seasons()
                : mergeSeasonFlags(before.seasons(), change.seasons());
        Series after = new Series(id, before.tvdbId(), before.tvMazeId(), before.tmdbId(), before.tvRageId(),
                before.imdbId(), before.title(), before.sortTitle(), before.cleanTitle(), before.titleSlug(),
                before.status(), before.overview(), before.network(), before.airTime(), before.runtime(),
                before.year(), before.firstAired(), before.lastAired(), before.genres(), before.certification(),
                before.ratings(), before.originalLanguage(), before.images(), before.alternateTitles(), seasons, path,
                change.qualityProfileId(), change.seasonFolder(), change.monitored(),
                orDefault(change.monitorNewItems(), before.monitorNewItems()),
                change.useSceneNumbering(), orDefault(change.seriesType(), before.seriesType()),
                change.tags() == null ? before.tags() : change.tags(), before.added(), before.lastInfoSync(),
                before.addOptions());
        mongo.save(after);
        applySeasonFlags(id, before.seasons(), seasons, actor);
        events.change("series", "updated", subject(after), actor, describe(before), describe(after));
        return after;
    }

    /**
     * Deletes a series and its episodes; with {@code deleteFiles}, its folder too, as Sonarr does (orch.md decision
     * 112: the user chose to). The folder must be inside a root folder and not be one.
     *
     * @param id the series
     * @param deleteFiles delete the series' folder from the NAS
     * @param actor who did it
     */
    public void delete(int id, boolean deleteFiles, String actor) {
        Series series = get(id);
        boolean deleted = false;
        if (deleteFiles && series.path() != null) {
            deleted = deleteFolder(series.path());
        }
        mongo.remove(Query.query(Criteria.where("seriesId").is(id)), Episode.class);
        mongo.remove(Query.query(Criteria.where("id").is(id)), Series.class);
        Map<String, Object> details = describe(series);
        details.put("deleteFiles", deleteFiles);
        details.put("folderDeleted", deleted);
        events.change("series", "deleted", subject(series), actor, details, null);
        LOG.info("Series deleted: {} (files deleted: {}) by {}", subject(series), deleted, actor);
    }

    /**
     * Reads a series' metadata again and updates it and its episodes: new episodes are added (monitored as their
     * season), changed ones updated (their monitored flag kept), and the ones the source no longer has removed.
     *
     * @param id the series
     * @param actor who asked: the user, or {@link CatasonarrEvents#SELF}
     * @return the series, or the same series unchanged when TVmaze no longer has it
     */
    public Series refresh(int id, String actor) {
        Series series = get(id);
        Optional<SeriesInfo> found = series.tvMazeId() > 0 ? metadata.byTvMaze(series.tvMazeId(), series.tmdbId())
                : metadata.byTvdb(series.tvdbId());
        if (found.isEmpty()) {
            LOG.warn("Series not refreshed, TVmaze no longer has it: {}", subject(series));
            return series;
        }
        SeriesInfo info = found.get();
        List<Season> seasons = new ArrayList<>(series.seasons());
        Set<Integer> known = seasons.stream().map(Season::seasonNumber).collect(Collectors.toSet());
        boolean monitorNew = "all".equals(series.monitorNewItems());
        info.episodes().stream().map(EpisodeInfo::seasonNumber).distinct().sorted()
                .filter(n -> !known.contains(n))
                .forEach(n -> seasons.add(new Season(n, monitorNew && n > 0)));
        seasons.sort(Comparator.comparingInt(Season::seasonNumber));
        Series after = fromInfo(id, info, series, series.titleSlug(), series.path(), series.qualityProfileId(),
                series.seasonFolder(), series.monitored(), series.monitorNewItems(), series.useSceneNumbering(),
                series.seriesType(), series.tags(), series.added(), clock.instant(), series.addOptions(), seasons);
        mongo.save(after);
        int[] counts = refreshEpisodes(id, info.episodes(), seasons);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("added", counts[0]);
        details.put("updated", counts[1]);
        details.put("removed", counts[2]);
        events.action("series", "refreshed", subject(after), actor, details);
        return after;
    }

    /**
     * Describes a series for an event.
     *
     * @param s the series
     * @return its values
     */
    public static Map<String, Object> describe(Series s) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", s.id());
        d.put("tvdbId", s.tvdbId());
        d.put("title", s.title());
        d.put("path", s.path());
        d.put("qualityProfileId", s.qualityProfileId());
        d.put("monitored", s.monitored());
        d.put("monitorNewItems", s.monitorNewItems());
        d.put("seasonFolder", s.seasonFolder());
        d.put("seriesType", s.seriesType());
        d.put("tags", s.tags());
        d.put("monitoredSeasons", s.seasons().stream().filter(Season::monitored).map(Season::seasonNumber).toList());
        return d;
    }

    private String path(AddRequest request, SeriesInfo info) {
        if (request.path() != null && !request.path().isBlank()) {
            String path = RootFolderService.normalize(request.path());
            if (!Path.of(path).isAbsolute()) {
                throw new BadRequestException("path", "Invalid Path");
            }
            return path;
        }
        if (request.rootFolderPath() == null || request.rootFolderPath().isBlank()) {
            throw new BadRequestException("rootFolderPath", "'Root Folder Path' must not be empty.");
        }
        String root = RootFolderService.normalize(request.rootFolderPath());
        if (!rootFolders.isRootFolder(root)) {
            throw new BadRequestException("rootFolderPath", "Root folder '" + root + "' does not exist");
        }
        return Path.of(root, folderName(info)).toString();
    }

    private static List<Season> seasons(SeriesInfo info, List<Season> asked) {
        Map<Integer, Boolean> wanted = new HashMap<>();
        if (asked != null) {
            asked.forEach(s -> wanted.put(s.seasonNumber(), s.monitored()));
        }
        TreeMap<Integer, Season> seasons = new TreeMap<>();
        info.episodes().forEach(e -> seasons.computeIfAbsent(e.seasonNumber(),
                n -> new Season(n, wanted.getOrDefault(n, n > 0))));
        wanted.forEach((n, monitored) -> seasons.putIfAbsent(n, new Season(n, monitored)));
        return new ArrayList<>(seasons.values());
    }

    private List<Episode> newEpisodes(int seriesId, List<EpisodeInfo> infos, List<Season> seasons) {
        if (infos.isEmpty()) {
            return List.of();
        }
        Map<Integer, Boolean> monitored = seasons.stream()
                .collect(Collectors.toMap(Season::seasonNumber, Season::monitored, (a, b) -> a));
        int next = sequences.reserve(EpisodeService.SEQUENCE, infos.size());
        List<Episode> created = new ArrayList<>();
        for (EpisodeInfo e : infos) {
            created.add(episode(next++, seriesId, e, monitored.getOrDefault(e.seasonNumber(), false)));
        }
        return created;
    }

    private int[] refreshEpisodes(int seriesId, List<EpisodeInfo> infos, List<Season> seasons) {
        List<Episode> current = episodes.bySeries(seriesId, null);
        Map<Integer, Episode> byTvMaze = current.stream()
                .collect(Collectors.toMap(Episode::tvMazeId, Function.identity(), (a, b) -> a));
        Map<Integer, Boolean> monitored = seasons.stream()
                .collect(Collectors.toMap(Season::seasonNumber, Season::monitored, (a, b) -> a));
        List<EpisodeInfo> added = infos.stream().filter(e -> !byTvMaze.containsKey(e.tvMazeId())).toList();
        Set<Integer> kept = new LinkedHashSet<>();
        BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Episode.class);
        int updated = 0;
        for (EpisodeInfo info : infos) {
            Episode old = byTvMaze.get(info.tvMazeId());
            if (old != null) {
                kept.add(old.id());
                Episode fresh = episode(old.id(), seriesId, info, old.monitored());
                if (!fresh.equals(old)) {
                    bulk.replaceOne(Query.query(Criteria.where("id").is(old.id())), fresh);
                    updated++;
                }
            }
        }
        if (!added.isEmpty()) {
            int next = sequences.reserve(EpisodeService.SEQUENCE, added.size());
            for (EpisodeInfo info : added) {
                bulk.insert(episode(next++, seriesId, info, monitored.getOrDefault(info.seasonNumber(), false)));
            }
        }
        List<Integer> removed = current.stream().map(Episode::id).filter(i -> !kept.contains(i)).toList();
        if (!removed.isEmpty()) {
            bulk.remove(Query.query(Criteria.where("id").in(removed)));
        }
        if (updated > 0 || !added.isEmpty() || !removed.isEmpty()) {
            bulk.execute();
        }
        return new int[] {added.size(), updated, removed.size()};
    }

    private void applySeasonFlags(int seriesId, List<Season> before, List<Season> after, String actor) {
        Map<Integer, Boolean> old = before.stream()
                .collect(Collectors.toMap(Season::seasonNumber, Season::monitored, (a, b) -> a));
        for (Season season : after) {
            Boolean was = old.get(season.seasonNumber());
            if (was != null && was != season.monitored()) {
                List<Integer> ids = episodes.bySeries(seriesId, season.seasonNumber()).stream().map(Episode::id)
                        .toList();
                episodes.setMonitored(ids, season.monitored(), actor);
            }
        }
    }

    private static List<Season> mergeSeasonFlags(List<Season> current, List<Season> asked) {
        Map<Integer, Boolean> wanted = asked.stream()
                .collect(Collectors.toMap(Season::seasonNumber, Season::monitored, (a, b) -> b));
        return current.stream().map(s -> new Season(s.seasonNumber(),
                wanted.getOrDefault(s.seasonNumber(), s.monitored()))).toList();
    }

    private boolean deleteFolder(String path) {
        Path folder = Path.of(path).normalize();
        Optional<RootFolder> root = rootFolders.containing(folder.toString());
        if (root.isEmpty()) {
            throw new BadRequestException("deleteFiles", "The series' folder " + folder
                    + " is not inside a root folder; delete it by hand");
        }
        if (!Files.exists(folder)) {
            return false;
        }
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failed) throws IOException {
                    if (failed != null) {
                        throw failed;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("The series' folder " + folder + " was not deleted: " + e.getMessage(),
                    e);
        }
    }

    private String slug(SeriesInfo info, int id) {
        String slug = Titles.slug(info.title());
        if (slug.isEmpty() || mongo.exists(Query.query(Criteria.where("titleSlug").is(slug)), Series.class)) {
            slug = (slug.isEmpty() ? "series" : slug) + "-" + (info.tvdbId() > 0 ? info.tvdbId() : id);
        }
        return slug;
    }

    private static Episode episode(int id, int seriesId, EpisodeInfo e, boolean monitored) {
        return new Episode(id, seriesId, e.tvMazeId(), e.seasonNumber(), e.episodeNumber(),
                e.absoluteEpisodeNumber(), e.title(), e.overview(), e.airDate(), e.airDateUtc(), e.runtime(),
                monitored, e.images());
    }

    private static Series fromInfo(int id, SeriesInfo info, Series old, String slug, String path, int profile,
                                   boolean seasonFolder, boolean monitored, String monitorNewItems,
                                   boolean useSceneNumbering, String seriesType, Set<Integer> tags, Instant added,
                                   Instant lastInfoSync, AddOptions addOptions, List<Season> seasons) {
        return new Series(id, info.tvdbId() > 0 ? info.tvdbId() : old == null ? 0 : old.tvdbId(), info.tvMazeId(),
                info.tmdbId() > 0 ? info.tmdbId() : old == null ? 0 : old.tmdbId(), info.tvRageId(), info.imdbId(),
                info.title(), Titles.sort(info.title()), Titles.clean(info.title()), slug, info.status(),
                info.overview(), info.network(), info.airTime(), info.runtime(), info.year(), info.firstAired(),
                info.lastAired(), info.genres(), old == null ? null : old.certification(), info.ratings(),
                info.originalLanguage(), info.images(), info.alternateTitles(), seasons, path, profile, seasonFolder,
                monitored, monitorNewItems, useSceneNumbering, seriesType, tags, added, lastInfoSync, addOptions);
    }

    private static Series withSeasons(Series s, List<Season> seasons) {
        return new Series(s.id(), s.tvdbId(), s.tvMazeId(), s.tmdbId(), s.tvRageId(), s.imdbId(), s.title(),
                s.sortTitle(), s.cleanTitle(), s.titleSlug(), s.status(), s.overview(), s.network(), s.airTime(),
                s.runtime(), s.year(), s.firstAired(), s.lastAired(), s.genres(), s.certification(), s.ratings(),
                s.originalLanguage(), s.images(), s.alternateTitles(), seasons, s.path(), s.qualityProfileId(),
                s.seasonFolder(), s.monitored(), s.monitorNewItems(), s.useSceneNumbering(), s.seriesType(), s.tags(),
                s.added(), s.lastInfoSync(), s.addOptions());
    }

    private static String subject(Series s) {
        return s.title() + " (" + s.id() + ")";
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
