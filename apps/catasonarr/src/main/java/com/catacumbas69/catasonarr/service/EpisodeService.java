package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Episodes and the statistics Sonarr shows of them (orch.md decision 112).
 */
@Service
public class EpisodeService {

    /** The collection of episodes. */
    public static final String COLLECTION = "catasonarr_episodes";
    /** Sequence name. */
    public static final String SEQUENCE = "episode";

    private final MongoTemplate mongo;
    private final CatasonarrEvents events;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param events events
     * @param clock the time
     */
    public EpisodeService(MongoTemplate mongo, CatasonarrEvents events, Clock clock) {
        this.mongo = mongo;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Returns a series' episodes, by season and number.
     *
     * @param seriesId the series
     * @param seasonNumber only this season, or {@code null} for all
     * @return the episodes
     */
    public List<Episode> bySeries(int seriesId, Integer seasonNumber) {
        Criteria c = Criteria.where("seriesId").is(seriesId);
        if (seasonNumber != null) {
            c = c.and("seasonNumber").is(seasonNumber);
        }
        return mongo.find(Query.query(c).with(Sort.by("seasonNumber", "episodeNumber")), Episode.class);
    }

    /**
     * Returns episodes by id.
     *
     * @param ids the ids
     * @return the episodes found, by series, season and number
     */
    public List<Episode> byIds(Collection<Integer> ids) {
        return mongo.find(Query.query(Criteria.where("id").in(ids))
                .with(Sort.by("seriesId", "seasonNumber", "episodeNumber")), Episode.class);
    }

    /**
     * Returns an episode.
     *
     * @param id the id
     * @return the episode
     */
    public Episode get(int id) {
        Episode e = mongo.findById(id, Episode.class);
        if (e == null) {
            throw new NotFoundException("Episode " + id);
        }
        return e;
    }

    /**
     * Sets whether episodes are monitored.
     *
     * @param ids the episodes
     * @param monitored the flag
     * @param actor who did it
     * @return the episodes as saved
     */
    public List<Episode> setMonitored(Collection<Integer> ids, boolean monitored, String actor) {
        if (ids.isEmpty()) {
            return List.of();
        }
        mongo.updateMulti(Query.query(Criteria.where("id").in(ids)), Update.update("monitored", monitored),
                Episode.class);
        List<Episode> episodes = byIds(ids);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("episodeIds", ids);
        details.put("monitored", monitored);
        events.action("episode", monitored ? "monitored" : "unmonitored", "episodes " + ids, actor, details);
        return episodes;
    }

    /**
     * Statistics of the given series' seasons, computed by MongoDB.
     *
     * @param seriesIds the series, or {@code null} for all
     * @return series id to season number to statistics
     */
    public Map<Integer, Map<Integer, Statistics>> statistics(Collection<Integer> seriesIds) {
        Date now = Date.from(clock.instant());
        Query fileQuery = seriesIds == null ? new Query() : Query.query(Criteria.where("seriesId").in(seriesIds));
        List<EpisodeFile> files = mongo.find(fileQuery, EpisodeFile.class);
        List<Integer> withFiles = files.stream().flatMap(f -> f.episodeIds().stream()).distinct().toList();
        Map<Integer, Map<Integer, Long>> sizes = new HashMap<>();
        for (EpisodeFile f : files) {
            sizes.computeIfAbsent(f.seriesId(), k -> new HashMap<>()).merge(f.seasonNumber(), f.size(), Long::sum);
        }
        Bson hasFile = new Document("$in", Arrays.asList("$_id", withFiles));
        Bson aired = new Document("$and", List.of("$monitored", new Document("$ne", Arrays.asList("$airDateUtc", null)),
                new Document("$lte", List.of("$airDateUtc", now))));
        Bson counted = new Document("$or", List.of(aired, hasFile));
        Bson upcoming = new Document("$and", List.of("$monitored", new Document("$gt", List.of("$airDateUtc", now))));
        List<Bson> pipeline = new ArrayList<>();
        if (seriesIds != null) {
            pipeline.add(Aggregates.match(Filters.in("seriesId", seriesIds)));
        }
        pipeline.add(Aggregates.group(
                new Document("series", "$seriesId").append("season", "$seasonNumber"),
                Accumulators.sum("total", 1),
                Accumulators.sum("aired", new Document("$cond", List.of(counted, 1, 0))),
                Accumulators.sum("files", new Document("$cond", List.of(hasFile, 1, 0))),
                Accumulators.min("next", new Document("$cond", Arrays.asList(upcoming, "$airDateUtc", null))),
                Accumulators.max("previous", new Document("$cond", Arrays.asList(aired, "$airDateUtc", null)))));
        Map<Integer, Map<Integer, Statistics>> result = new HashMap<>();
        for (Document d : mongo.getCollection(COLLECTION).aggregate(pipeline)) {
            Document key = d.get("_id", Document.class);
            int series = key.getInteger("series");
            int season = key.getInteger("season");
            long size = sizes.getOrDefault(series, Map.of()).getOrDefault(season, 0L);
            result.computeIfAbsent(series, s -> new HashMap<>()).put(season, new Statistics(season,
                    d.getInteger("total"), d.getInteger("aired"), d.getInteger("files"), size,
                    instant(d.getDate("next")), instant(d.getDate("previous"))));
        }
        return result;
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
