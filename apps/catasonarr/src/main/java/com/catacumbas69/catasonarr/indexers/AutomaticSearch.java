package com.catacumbas69.catasonarr.indexers;

import com.catacumbas69.catasonarr.decision.CustomFormatCalculator;
import com.catacumbas69.catasonarr.decision.DownloadDecision;
import com.catacumbas69.catasonarr.decision.DownloadDecisionMaker;
import com.catacumbas69.catasonarr.decision.Library;
import com.catacumbas69.catasonarr.decision.Upgradable;
import com.catacumbas69.catasonarr.download.DecisionProcessor;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.Indexer;
import com.catacumbas69.catasonarr.model.PendingRelease;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Settings;
import com.catacumbas69.catasonarr.service.CatasonarrEvents;
import com.catacumbas69.catasonarr.service.EpisodeFileService;
import com.catacumbas69.catasonarr.service.NotFoundException;
import com.catacumbas69.catasonarr.service.PendingReleaseService;
import com.catacumbas69.catasonarr.service.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sonarr's searches and its RSS sync (orch.md decision 122): {@code RssSync} every
 * {@link Settings#rssSyncInterval()} minutes (15 by default) and on demand, {@code SeriesSearch},
 * {@code SeasonSearch}, {@code EpisodeSearch}, {@code MissingEpisodeSearch} and {@code CutoffUnmetEpisodeSearch}.
 * Each grabs the best release of each episode and holds back the delayed ones. The RSS sync also looks again at
 * the releases held back, and forgets those two weeks old.
 */
@Service
public class AutomaticSearch {

    private static final Logger LOG = LoggerFactory.getLogger(AutomaticSearch.class);
    private static final Duration PENDING_KEPT = Duration.ofDays(14);

    /**
     * What a search or a sync did.
     *
     * @param releases releases looked at
     * @param grabbed releases grabbed
     * @param pending releases held back
     */
    public record Result(int releases, List<String> grabbed, List<String> pending) {

        /**
         * Sonarr's message for it.
         *
         * @return e.g. {@code 200 releases, 1 grabbed, 0 pending}
         */
        public String message() {
            return releases + " releases, " + grabbed.size() + " grabbed, " + pending.size() + " pending";
        }
    }

    private final IndexerService indexers;
    private final TorznabClient torznab;
    private final ReleaseSearchService search;
    private final DownloadDecisionMaker decisions;
    private final DecisionProcessor processor;
    private final PendingReleaseService pending;
    private final Library library;
    private final EpisodeFileService files;
    private final CustomFormatCalculator formats;
    private final SettingsService settings;
    private final CatasonarrEvents events;
    private final Clock clock;
    private final AtomicBoolean syncing = new AtomicBoolean();
    private volatile Instant lastSync = Instant.EPOCH;

    /**
     * Creates the service.
     *
     * @param indexers the indexers
     * @param torznab the feeds
     * @param search the searches
     * @param decisions the decision maker
     * @param processor what grabs and holds back
     * @param pending held-back releases
     * @param library the series and episodes
     * @param files the episodes' files
     * @param formats custom formats
     * @param settings the settings
     * @param events events
     * @param clock the time
     */
    public AutomaticSearch(IndexerService indexers, TorznabClient torznab, ReleaseSearchService search,
                           DownloadDecisionMaker decisions, DecisionProcessor processor, PendingReleaseService pending,
                           Library library, EpisodeFileService files, CustomFormatCalculator formats,
                           SettingsService settings, CatasonarrEvents events, Clock clock) {
        this.indexers = indexers;
        this.torznab = torznab;
        this.search = search;
        this.decisions = decisions;
        this.processor = processor;
        this.pending = pending;
        this.library = library;
        this.files = files;
        this.formats = formats;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    /** Runs the RSS sync when its interval has passed (checked every minute). */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
    public void tick() {
        try {
            int interval = settings.get().rssSyncInterval();
            if (interval > 0 && lastSync.plus(interval, ChronoUnit.MINUTES).isBefore(clock.instant())) {
                rssSync(CatasonarrEvents.SELF);
            }
        } catch (RuntimeException e) {
            LOG.warn("RSS sync not run, tried again in a minute: {}", e.getMessage());
        }
    }

    /**
     * Reads every RSS indexer's newest releases, plus those held back, and grabs what is wanted.
     *
     * @param actor who asked
     * @return what it did, or {@code null} when a sync was already running
     */
    public Result rssSync(String actor) {
        if (!syncing.compareAndSet(false, true)) {
            return null;
        }
        try {
            lastSync = clock.instant();
            List<ReleaseInfo> releases = new ArrayList<>();
            for (Indexer indexer : indexers.all().stream().filter(Indexer::enableRss).toList()) {
                try {
                    releases.addAll(torznab.rss(indexer));
                } catch (RuntimeException e) {
                    LOG.warn("RSS of {} failed: {}", indexer.name(), e.getMessage());
                }
            }
            Instant forget = clock.instant().minus(PENDING_KEPT);
            List<PendingRelease> held = pending.all();
            pending.remove(held.stream().filter(p -> p.added().isBefore(forget)).map(PendingRelease::id).toList());
            List<String> known = releases.stream().map(ReleaseInfo::guid).toList();
            List<PendingRelease> stillHeld = held.stream().filter(p -> !p.added().isBefore(forget)).toList();
            stillHeld.stream().map(PendingRelease::release).filter(r -> !known.contains(r.guid()))
                    .forEach(releases::add);
            pending.remove(stillHeld.stream().map(PendingRelease::id).toList());
            Result result = process(decisions.rss(releases), releases.size(), actor);
            events.action("rss", "synced", "RSS sync", actor, details(result));
            LOG.info("RSS sync: {}", result.message());
            return result;
        } finally {
            syncing.set(false);
        }
    }

    /**
     * Searches for episodes.
     *
     * @param episodeIds the episodes
     * @param actor who asked
     * @return what it did
     */
    public Result episodeSearch(List<Integer> episodeIds, String actor) {
        List<DownloadDecision> all = new ArrayList<>();
        for (int id : episodeIds) {
            all.addAll(search.episodeSearch(id, true, false));
        }
        return process(all, all.size(), actor);
    }

    /**
     * Searches for a season.
     *
     * @param seriesId the series
     * @param seasonNumber the season
     * @param actor who asked
     * @return what it did
     */
    public Result seasonSearch(int seriesId, int seasonNumber, String actor) {
        List<DownloadDecision> all = search.seasonSearch(seriesId, seasonNumber, true, true, false);
        return process(all, all.size(), actor);
    }

    /**
     * Searches every monitored season of a series, as Sonarr's {@code SeriesSearch}.
     *
     * @param seriesId the series
     * @param userInvoked asked by someone
     * @param actor who asked
     * @return what it did
     */
    public Result seriesSearch(int seriesId, boolean userInvoked, String actor) {
        Series series = library.series(seriesId).orElseThrow(() -> new NotFoundException("Series " + seriesId));
        List<DownloadDecision> all = new ArrayList<>();
        series.seasons().stream().filter(s -> s.monitored()).sorted(Comparator.comparingInt(s -> s.seasonNumber()))
                .forEach(s -> all.addAll(search.seasonSearch(seriesId, s.seasonNumber(), true, userInvoked, false)));
        return process(all, all.size(), actor);
    }

    /**
     * Searches the monitored, aired episodes without a file, of a series or of all: a season search where two or
     * more of a season are missing, else an episode search.
     *
     * @param seriesId the series, or {@code null} for all
     * @param actor who asked
     * @return what it did
     */
    public Result missingSearch(Integer seriesId, String actor) {
        List<Series> series = seriesId == null ? library.allSeries()
                : List.of(library.series(seriesId).orElseThrow(() -> new NotFoundException("Series " + seriesId)));
        Instant now = clock.instant();
        List<DownloadDecision> all = new ArrayList<>();
        for (Series s : series.stream().filter(Series::monitored).toList()) {
            Map<Integer, EpisodeFile> withFiles = files.byEpisode(List.of(s.id()));
            Map<Integer, List<Episode>> missing = library.episodes(s.id()).stream()
                    .filter(Episode::monitored).filter(e -> e.airDateUtc() != null && e.airDateUtc().isBefore(now))
                    .filter(e -> !withFiles.containsKey(e.id()))
                    .filter(e -> s.seasons().stream().anyMatch(x -> x.seasonNumber() == e.seasonNumber()
                            && x.monitored()))
                    .collect(Collectors.groupingBy(Episode::seasonNumber, LinkedHashMap::new, Collectors.toList()));
            missing.forEach((season, episodes) -> {
                if (episodes.size() > 1 && !"daily".equals(s.seriesType())) {
                    all.addAll(search.seasonSearch(s.id(), season, true, false, false));
                } else {
                    episodes.forEach(e -> all.addAll(search.episodeSearch(e.id(), false, false)));
                }
            });
        }
        return process(all, all.size(), actor);
    }

    /**
     * Searches the monitored episodes whose file is below the profile's cutoff, as Sonarr's
     * {@code CutoffUnmetEpisodeSearch}.
     *
     * @param actor who asked
     * @return what it did
     */
    public Result cutoffUnmetSearch(String actor) {
        return cutoffUnmetSearch(null, actor);
    }

    /**
     * Searches for better releases of one series' monitored episodes whose file has not met the cutoff (a search
     * asked when the series is added).
     *
     * @param seriesId the series, or {@code null} for all
     * @param actor who asked
     * @return what it did
     */
    public Result cutoffUnmetSearch(Integer seriesId, String actor) {
        List<DownloadDecision> all = new ArrayList<>();
        for (Series s : library.allSeries().stream().filter(Series::monitored)
                .filter(x -> seriesId == null || x.id() == seriesId).toList()) {
            QualityProfile profile = library.profile(s);
            if (profile == null) {
                continue;
            }
            Map<Integer, EpisodeFile> withFiles = files.byEpisode(List.of(s.id()));
            for (Episode e : library.episodes(s.id()).stream().filter(Episode::monitored).toList()) {
                EpisodeFile f = withFiles.get(e.id());
                if (f != null && Upgradable.cutoffNotMet(profile, f.quality(), formats.forFile(f, s), null)) {
                    all.addAll(search.episodeSearch(e.id(), false, false));
                }
            }
        }
        return process(all, all.size(), actor);
    }

    private Result process(List<DownloadDecision> all, int releases, String actor) {
        DecisionProcessor.Result r = processor.process(all, actor);
        return new Result(releases, r.grabbed(), r.pending());
    }

    private static Map<String, Object> details(Result r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("releases", r.releases());
        d.put("grabbed", r.grabbed());
        d.put("pending", r.pending());
        return d;
    }
}
