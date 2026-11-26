package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.download.TrackedDownloadService;
import com.catacumbas69.catasonarr.indexers.AutomaticSearch;
import com.catacumbas69.catasonarr.media.DiskScan;
import com.catacumbas69.catasonarr.model.AddOptions;
import com.catacumbas69.catasonarr.model.Command;
import com.catacumbas69.catasonarr.model.Series;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Sonarr's commands (orch.md decisions 112 and 122), run in the background: {@code RefreshSeries} (then a rescan),
 * {@code RescanSeries}, one at a time; and the searches ({@code SeriesSearch}, {@code SeasonSearch},
 * {@code EpisodeSearch}, {@code MissingEpisodeSearch}, {@code CutoffUnmetEpisodeSearch}), {@code RssSync} and
 * {@code RefreshMonitoredDownloads}, one at a time on their own worker, so a long refresh does not hold a search.
 */
@Service
public class CommandService implements AutoCloseable {

    /** Sequence name. */
    public static final String SEQUENCE = "command";

    private static final Logger LOG = LoggerFactory.getLogger(CommandService.class);
    private static final Set<String> DISK = Set.of("refreshseries", "rescanseries");
    private static final Set<String> SEARCHES = Set.of("seriessearch", "seasonsearch", "episodesearch",
            "missingepisodesearch", "cutoffunmetepisodesearch", "rsssync", "refreshmonitoreddownloads");

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final SeriesService series;
    private final SeriesRefresher refresher;
    private final DiskScan disk;
    private final AutomaticSearch search;
    private final TrackedDownloadService downloads;
    private final CatasonarrEvents events;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual()
            .name("catasonarr-command").factory());
    private final ExecutorService searcher = Executors.newSingleThreadExecutor(Thread.ofVirtual()
            .name("catasonarr-search").factory());

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param series the series
     * @param refresher the refresh of every series
     * @param disk the rescan of a series' folder
     * @param search the searches (lazy: they reach the queue, which reaches back)
     * @param downloads the downloads followed
     * @param events events
     * @param clock the time
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public CommandService(MongoTemplate mongo, Sequences sequences, SeriesService series, SeriesRefresher refresher,
                          DiskScan disk, @Lazy AutomaticSearch search, @Lazy TrackedDownloadService downloads,
                          CatasonarrEvents events, Clock clock) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.series = series;
        this.refresher = refresher;
        this.disk = disk;
        this.search = search;
        this.downloads = downloads;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Receives a command.
     *
     * @param body what was sent; {@code name} is the command
     * @param actor who sent it
     * @return the command as kept
     */
    public Command submit(Map<String, Object> body, String actor) {
        Object nameValue = body.get("name");
        if (nameValue == null || nameValue.toString().isBlank()) {
            throw new BadRequestException("name", "'Name' must not be empty.");
        }
        String name = nameValue.toString();
        String key = name.toLowerCase(Locale.ROOT);
        if (!DISK.contains(key) && !SEARCHES.contains(key)) {
            throw new BadRequestException("name", "Unknown command: " + name);
        }
        Command command = record(name, body, actor);
        (DISK.contains(key) ? worker : searcher).submit(() -> run(command.id(), () -> execute(key, body, actor)));
        return command;
    }

    /**
     * What follows adding a series, as Sonarr does: its folder is scanned, then the search asked in
     * {@code addOptions} runs (missing episodes, cutoff unmet, or both as a {@code SeriesSearch}).
     *
     * @param added the series
     * @param options what was asked
     * @param actor who added it
     * @return the rescan command
     */
    public Command seriesAdded(Series added, AddOptions options, String actor) {
        Command rescan = submit(Map.of("name", "RescanSeries", "seriesId", added.id()), actor);
        if (options == null || !options.searchForMissingEpisodes() && !options.searchForCutoffUnmetEpisodes()) {
            return rescan;
        }
        String name = options.searchForMissingEpisodes() && options.searchForCutoffUnmetEpisodes() ? "SeriesSearch"
                : options.searchForMissingEpisodes() ? "MissingEpisodeSearch" : "CutoffUnmetEpisodeSearch";
        Map<String, Object> body = Map.of("name", name, "seriesId", added.id());
        Command search = record(name, body, actor);
        String key = name.toLowerCase(Locale.ROOT);
        // Kept now, so it shows as queued; run on the search worker once the rescan before it has ended
        worker.submit(() -> searcher.submit(() -> run(search.id(), () -> execute(key, body, actor))));
        return rescan;
    }

    /** Keeps a command as queued and tells it as received. */
    private Command record(String name, Map<String, Object> body, String actor) {
        Command command = new Command(sequences.next(SEQUENCE), name, body, "queued", "unknown", null, "manual",
                clock.instant(), null, null);
        mongo.insert(command);
        Map<String, Object> details = new LinkedHashMap<>(body);
        details.put("commandId", command.id());
        events.action("command", "received", name, actor, details);
        return command;
    }

    /**
     * Returns the commands not ended yet and those of the last hour, newest first.
     *
     * @return the commands
     */
    public List<Command> recent() {
        Instant hourAgo = clock.instant().minusSeconds(3600);
        Query q = Query.query(new Criteria().orOperator(Criteria.where("ended").is(null),
                Criteria.where("ended").gte(hourAgo))).with(Sort.by(Sort.Direction.DESC, "id"));
        return mongo.find(q, Command.class);
    }

    /**
     * Returns a command.
     *
     * @param id the id
     * @return the command
     */
    public Command get(int id) {
        Command c = mongo.findById(id, Command.class);
        if (c == null) {
            throw new NotFoundException("Command " + id);
        }
        return c;
    }

    /** Stops the background workers. */
    @Override
    public void close() {
        worker.shutdownNow();
        searcher.shutdownNow();
    }

    private void run(int commandId, Supplier<String> work) {
        set(commandId, Update.update("status", "started").set("started", clock.instant()));
        try {
            String message = work.get();
            set(commandId, Update.update("status", "completed").set("result", "successful").set("message", message)
                    .set("ended", clock.instant()));
        } catch (RuntimeException e) {
            LOG.warn("Command {} failed: {}", commandId, e.getMessage());
            set(commandId, Update.update("status", "failed").set("result", "unsuccessful")
                    .set("message", e.getMessage()).set("ended", clock.instant()));
        }
    }

    private String execute(String key, Map<String, Object> body, String actor) {
        List<Integer> ids = seriesIds(body);
        Integer seriesId = ids.isEmpty() ? null : ids.get(0);
        AutomaticSearch.Result r;
        switch (key) {
            case "refreshseries":
                return refresh(ids, actor);
            case "rescanseries":
                return rescan(ids, actor);
            case "rsssync":
                r = search.rssSync(actor);
                return r == null ? "An RSS sync was already running" : r.message();
            case "refreshmonitoreddownloads":
                return downloads.refresh() ? "Downloads refreshed" : "A refresh was already running";
            case "seriessearch":
                r = search.seriesSearch(required(seriesId, "seriesId"), true, actor);
                return r.message();
            case "seasonsearch":
                r = search.seasonSearch(required(seriesId, "seriesId"), required(number(body.get("seasonNumber")),
                        "seasonNumber"), actor);
                return r.message();
            case "episodesearch":
                List<Integer> episodeIds = numbers(body.get("episodeIds"));
                if (episodeIds.isEmpty()) {
                    throw new BadRequestException("episodeIds", "episodeIds must not be empty");
                }
                return search.episodeSearch(episodeIds, actor).message();
            case "missingepisodesearch":
                return search.missingSearch(seriesId, actor).message();
            case "cutoffunmetepisodesearch":
                return search.cutoffUnmetSearch(seriesId, actor).message();
            default:
                throw new BadRequestException("name", "Unknown command: " + key);
        }
    }

    private String refresh(List<Integer> seriesIds, String actor) {
        if (seriesIds.isEmpty()) {
            int done = refresher.refreshAll(true, actor);
            return done < 0 ? "A refresh was already running" : "Refreshed " + done + " series";
        }
        for (int id : seriesIds) {
            disk.rescan(series.refresh(id, actor), actor);
        }
        return "Refreshed and rescanned " + seriesIds.size() + " series";
    }

    private String rescan(List<Integer> seriesIds, String actor) {
        List<Series> all = seriesIds.isEmpty() ? series.all() : seriesIds.stream().map(series::get).toList();
        List<String> messages = new ArrayList<>();
        for (Series s : all) {
            messages.add(disk.rescan(s, actor).message());
        }
        return all.size() == 1 ? messages.get(0) : "Rescanned " + all.size() + " series";
    }

    private static int required(Integer value, String name) {
        if (value == null) {
            throw new BadRequestException(name, name + " must be provided");
        }
        return value;
    }

    private static Integer number(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static List<Integer> numbers(Object o) {
        List<Integer> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            list.stream().filter(Number.class::isInstance).map(n -> ((Number) n).intValue()).forEach(out::add);
        }
        return out;
    }

    private void set(int id, Update update) {
        mongo.updateFirst(Query.query(Criteria.where("id").is(id)), update, Command.class);
    }

    private static List<Integer> seriesIds(Map<String, Object> body) {
        List<Integer> ids = new ArrayList<>();
        Object one = body.get("seriesId");
        if (one instanceof Number n) {
            ids.add(n.intValue());
        }
        if (body.get("seriesIds") instanceof List<?> many) {
            many.stream().filter(Number.class::isInstance).map(n -> ((Number) n).intValue()).forEach(ids::add);
        }
        return ids;
    }
}
