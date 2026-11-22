package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.Command;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Sonarr's commands (orch.md decision 112). {@code RefreshSeries} runs, one command at a time, in the background.
 * The searches ({@code SeriesSearch}, {@code EpisodeSearch}, {@code MissingEpisodeSearch}…) are accepted and kept,
 * as asked when Overseerr adds a series, but end {@code unsuccessful}: searching comes in phase 2.
 */
@Service
public class CommandService implements AutoCloseable {

    /** Sequence name. */
    public static final String SEQUENCE = "command";

    private static final Logger LOG = LoggerFactory.getLogger(CommandService.class);
    private static final Set<String> SEARCHES = Set.of("seriessearch", "seasonsearch", "episodesearch",
            "missingepisodesearch", "cutoffunmetepisodesearch", "rsssync");
    private static final String NOT_YET = "Not done: Catasonarr does not search yet (phase 2)";

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final SeriesService series;
    private final SeriesRefresher refresher;
    private final CatasonarrEvents events;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual()
            .name("catasonarr-command").factory());

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param series the series
     * @param refresher the refresh of every series
     * @param events events
     * @param clock the time
     */
    public CommandService(MongoTemplate mongo, Sequences sequences, SeriesService series, SeriesRefresher refresher,
                          CatasonarrEvents events, Clock clock) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.series = series;
        this.refresher = refresher;
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
        Instant now = clock.instant();
        String key = name.toLowerCase(Locale.ROOT);
        Command command;
        if ("refreshseries".equals(key)) {
            command = new Command(sequences.next(SEQUENCE), name, body, "queued", "unknown", null, "manual", now,
                    null, null);
            mongo.insert(command);
            final int id = command.id();
            List<Integer> ids = seriesIds(body);
            worker.submit(() -> refresh(id, ids, actor));
        } else if (SEARCHES.contains(key) || "rescanseries".equals(key)) {
            command = new Command(sequences.next(SEQUENCE), name, body, "completed", "unsuccessful", NOT_YET,
                    "manual", now, now, now);
            mongo.insert(command);
        } else {
            throw new BadRequestException("name", "Unknown command: " + name);
        }
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

    /** Stops the background worker. */
    @Override
    public void close() {
        worker.shutdownNow();
    }

    private void refresh(int commandId, List<Integer> seriesIds, String actor) {
        set(commandId, Update.update("status", "started").set("started", clock.instant()));
        try {
            String message;
            if (seriesIds.isEmpty()) {
                int done = refresher.refreshAll(true, actor);
                message = done < 0 ? "A refresh was already running" : "Refreshed " + done + " series";
            } else {
                for (int id : seriesIds) {
                    series.refresh(id, actor);
                }
                message = "Refreshed " + seriesIds.size() + " series";
            }
            set(commandId, Update.update("status", "completed").set("result", "successful").set("message", message)
                    .set("ended", clock.instant()));
        } catch (RuntimeException e) {
            LOG.warn("Command {} failed: {}", commandId, e.getMessage());
            set(commandId, Update.update("status", "failed").set("result", "unsuccessful")
                    .set("message", e.getMessage()).set("ended", clock.instant()));
        }
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
