package com.catacumbas69.catasonarr.indexers;

import com.catacumbas69.catasonarr.decision.DownloadDecision;
import com.catacumbas69.catasonarr.decision.DownloadDecisionMaker;
import com.catacumbas69.catasonarr.decision.Library;
import com.catacumbas69.catasonarr.decision.SearchCriteria;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.Indexer;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.service.NotFoundException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Searches the indexers, as Sonarr's {@code ReleaseSearchService} and {@code NewznabRequestGenerator} (GPL-3.0,
 * orch.md decision 122): a single episode ({@code season} and {@code ep}), a season, a daily episode (the date as
 * season and month/day), an anime episode (its absolute number) or a special (its title); by TheTVDB's, IMDb's and
 * TVmaze's ids when the feed supports them, else by title. Every indexer at once, each on a virtual thread.
 */
@Service
public class ReleaseSearchService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseSearchService.class);
    private static final int MAX_PAGES = 10;

    private final IndexerService indexers;
    private final TorznabClient torznab;
    private final DownloadDecisionMaker decisions;
    private final Library library;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Integer, String> lastErrors = new ConcurrentHashMap<>();

    /**
     * Creates the service.
     *
     * @param indexers the indexers
     * @param torznab the feeds
     * @param decisions the decision maker
     * @param library the series and episodes
     */
    public ReleaseSearchService(IndexerService indexers, TorznabClient torznab, DownloadDecisionMaker decisions,
                                Library library) {
        this.indexers = indexers;
        this.torznab = torznab;
        this.decisions = decisions;
        this.library = library;
    }

    /**
     * Searches for one episode.
     *
     * @param episodeId the episode
     * @param userInvoked asked by someone
     * @param interactive the interactive search (every indexer with interactive search on)
     * @return the decisions
     */
    public List<DownloadDecision> episodeSearch(int episodeId, boolean userInvoked, boolean interactive) {
        Episode episode = library.episodes(List.of(episodeId)).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Episode " + episodeId));
        Series series = library.series(episode.seriesId()).orElseThrow(() -> new NotFoundException("Series "
                + episode.seriesId()));
        String kind = "daily".equals(series.seriesType()) && episode.airDate() != null ? "daily"
                : "anime".equals(series.seriesType()) && episode.absoluteEpisodeNumber() != null ? "anime"
                : episode.seasonNumber() == 0 ? "special" : "single";
        SearchCriteria c = new SearchCriteria(series, List.of(episode), kind, episode.seasonNumber(),
                episode.episodeNumber(), episode.airDate(), episode.absoluteEpisodeNumber(), userInvoked, interactive,
                !userInvoked);
        return search(c);
    }

    /**
     * Searches for a season (a season pack, or its episodes as the releases come).
     *
     * @param seriesId the series
     * @param seasonNumber the season
     * @param monitoredOnly only its monitored episodes are wanted
     * @param userInvoked asked by someone
     * @param interactive the interactive search
     * @return the decisions
     */
    public List<DownloadDecision> seasonSearch(int seriesId, int seasonNumber, boolean monitoredOnly,
                                               boolean userInvoked, boolean interactive) {
        Series series = library.series(seriesId).orElseThrow(() -> new NotFoundException("Series " + seriesId));
        List<Episode> episodes = library.season(seriesId, seasonNumber).stream()
                .filter(e -> !monitoredOnly || e.monitored()).toList();
        if (episodes.isEmpty()) {
            return List.of();
        }
        if ("daily".equals(series.seriesType())) {
            List<DownloadDecision> all = new ArrayList<>();
            for (Episode e : episodes) {
                all.addAll(episodeSearch(e.id(), userInvoked, interactive));
            }
            return all;
        }
        String kind = "anime".equals(series.seriesType()) ? "animeSeason" : "season";
        return search(new SearchCriteria(series, episodes, kind, seasonNumber, 0, null, null, userInvoked,
                interactive, monitoredOnly));
    }

    /**
     * Runs a search on every indexer it may use, and decides on what they return.
     *
     * @param c what to search
     * @return the decisions
     */
    public List<DownloadDecision> search(SearchCriteria c) {
        List<Indexer> usable = indexers.all().stream()
                .filter(i -> c.interactiveSearch() ? i.enableInteractiveSearch() : i.enableAutomaticSearch())
                .filter(i -> i.tags() == null || i.tags().isEmpty() || c.series().tags() != null
                        && i.tags().stream().anyMatch(c.series().tags()::contains))
                .toList();
        List<Future<List<ReleaseInfo>>> futures = new ArrayList<>();
        for (Indexer indexer : usable) {
            futures.add(pool.submit(() -> fetch(indexer, c)));
        }
        List<ReleaseInfo> releases = new ArrayList<>();
        for (Future<List<ReleaseInfo>> f : futures) {
            try {
                releases.addAll(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException e) {
                LOG.warn("Search failed: {}", e.getCause().toString());
            }
        }
        LOG.info("Search for {} {} found {} releases on {} indexers", c.series().title(), describe(c),
                releases.size(), usable.size());
        return decisions.search(releases, c);
    }

    /**
     * The last error of each indexer, for the page.
     *
     * @return indexer id to message
     */
    public Map<Integer, String> lastErrors() {
        return Map.copyOf(lastErrors);
    }

    /** One indexer's releases: by ids when it can, by title when ids find nothing. */
    private List<ReleaseInfo> fetch(Indexer indexer, SearchCriteria c) {
        try {
            TorznabClient.Capabilities caps = torznab.capabilities(indexer);
            Set<String> p = caps.tvSearchParameters();
            String params = parameters(c, indexer);
            boolean anime = c.kind().startsWith("anime");
            List<ReleaseInfo> found = new ArrayList<>();
            if (!"special".equals(c.kind())) {
                String ids = ids(c.series(), p);
                if (!ids.isEmpty()) {
                    found.addAll(torznab.search(indexer, ids + params, anime, MAX_PAGES));
                }
            }
            if (found.isEmpty() && (p.contains("q") || p.isEmpty())) {
                found.addAll(torznab.search(indexer, "&q=" + query(c) + ("special".equals(c.kind()) ? "" : params),
                        anime, MAX_PAGES));
            }
            lastErrors.remove(indexer.id());
            return found;
        } catch (RuntimeException e) {
            lastErrors.put(indexer.id(), e.getMessage());
            LOG.warn("Indexer {} failed: {}", indexer.name(), e.getMessage());
            return List.of();
        }
    }

    private static String ids(Series s, Set<String> supported) {
        StringBuilder ids = new StringBuilder();
        if (supported.contains("tvdbid") && s.tvdbId() > 0) {
            ids.append("&tvdbid=").append(s.tvdbId());
        }
        if (supported.contains("imdbid") && s.imdbId() != null && !s.imdbId().isBlank()) {
            ids.append("&imdbid=").append(s.imdbId());
        }
        if (supported.contains("tvmazeid") && s.tvMazeId() > 0) {
            ids.append("&tvmazeid=").append(s.tvMazeId());
        }
        if (supported.contains("tmdbid") && s.tmdbId() > 0) {
            ids.append("&tmdbid=").append(s.tmdbId());
        }
        return ids.toString();
    }

    /** Sonarr's season and episode parameters for each kind of search. */
    private static String parameters(SearchCriteria c, Indexer indexer) {
        switch (c.kind()) {
            case "single":
                return "&season=" + season(c.seasonNumber()) + "&ep=" + c.episodeNumber();
            case "season", "animeSeason":
                return "&season=" + season(c.seasonNumber());
            case "daily":
                String[] date = c.airDate().split("-");
                return "&season=" + date[0] + "&ep=" + date[1] + "/" + date[2];
            case "anime":
                boolean standard = Boolean.TRUE.equals(indexer.settings().get("animeStandardFormatSearch"));
                return standard ? "&season=" + season(c.seasonNumber()) + "&ep=" + c.episodeNumber()
                        : "&q=" + String.format("%02d", c.absoluteEpisodeNumber());
            default:
                return "";
        }
    }

    /** The title searched for: the series' title, and a special's title. */
    private static String query(SearchCriteria c) {
        String title = c.series().title().replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").strip();
        if ("special".equals(c.kind()) && !c.episodes().isEmpty() && c.episodes().get(0).title() != null) {
            title = title + " " + c.episodes().get(0).title();
        }
        return URLEncoder.encode(title, StandardCharsets.UTF_8);
    }

    private static String season(int season) {
        return season == 0 ? "00" : String.valueOf(season);
    }

    private static String describe(SearchCriteria c) {
        return switch (c.kind()) {
            case "single" -> String.format("S%02dE%02d", c.seasonNumber(), c.episodeNumber());
            case "season", "animeSeason" -> "season " + c.seasonNumber();
            case "daily" -> c.airDate();
            case "anime" -> "#" + c.absoluteEpisodeNumber();
            default -> "special " + (c.episodes().isEmpty() ? "" : c.episodes().get(0).title());
        };
    }

    /** Stops the search threads. */
    @Override
    public void close() {
        pool.shutdownNow();
    }
}
