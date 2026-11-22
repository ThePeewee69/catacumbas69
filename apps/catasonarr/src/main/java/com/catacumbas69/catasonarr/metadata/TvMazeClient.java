package com.catacumbas69.catasonarr.metadata;

import com.catacumbas69.catasonarr.config.CatasonarrProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * TVmaze's public API (orch.md decision 112): free, without a key, CC BY-SA (credited on the page). Calls are spaced
 * by {@code catasonarr.metadata.tvmaze-spacing}, so Catasonarr stays under TVmaze's 20 calls every 10 s; a {@code 429}
 * waits 10 s and tries once more.
 */
@Component
public class TvMazeClient {

    private static final Logger LOG = LoggerFactory.getLogger(TvMazeClient.class);
    private static final Duration TOO_MANY_WAIT = Duration.ofSeconds(10);

    private final RestClient http;
    private final long spacingNanos;
    private long nextCall;

    /**
     * Creates the client.
     *
     * @param http the HTTP client for TVmaze, from {@code CoreConfig}
     * @param properties the spacing between calls
     */
    public TvMazeClient(@Qualifier("tvmazeRestClient") RestClient http, CatasonarrProperties properties) {
        this.http = http;
        this.spacingNanos = properties.metadata().tvmazeSpacing().toNanos();
    }

    /**
     * Searches shows by name.
     *
     * @param query the words
     * @return the shows found, best first
     */
    public List<JsonNode> search(String query) {
        JsonNode results = call("search " + query,
                () -> http.get().uri("/search/shows?q={q}", query).retrieve().body(JsonNode.class));
        List<JsonNode> shows = new ArrayList<>();
        if (results != null) {
            results.forEach(r -> shows.add(r.path("show")));
        }
        return shows;
    }

    /**
     * Finds a show by TheTVDB's id.
     *
     * @param tvdbId TheTVDB's id
     * @return the show, or empty when TVmaze does not know it
     */
    public Optional<JsonNode> lookupTvdb(int tvdbId) {
        return optional("lookup tvdb " + tvdbId,
                () -> http.get().uri("/lookup/shows?thetvdb={id}", tvdbId).retrieve().body(JsonNode.class));
    }

    /**
     * Finds a show by IMDb's id.
     *
     * @param imdbId e.g. {@code tt0944947}
     * @return the show, or empty when TVmaze does not know it
     */
    public Optional<JsonNode> lookupImdb(String imdbId) {
        return optional("lookup imdb " + imdbId,
                () -> http.get().uri("/lookup/shows?imdb={id}", imdbId).retrieve().body(JsonNode.class));
    }

    /**
     * Reads a show.
     *
     * @param tvMazeId TVmaze's id
     * @return the show, or empty when TVmaze no longer has it
     */
    public Optional<JsonNode> show(int tvMazeId) {
        return optional("show " + tvMazeId,
                () -> http.get().uri("/shows/{id}", tvMazeId).retrieve().body(JsonNode.class));
    }

    /**
     * Reads a show's episodes, with the specials.
     *
     * @param tvMazeId TVmaze's id
     * @return TVmaze's array
     */
    public JsonNode episodes(int tvMazeId) {
        return call("episodes " + tvMazeId,
                () -> http.get().uri("/shows/{id}/episodes?specials=1", tvMazeId).retrieve().body(JsonNode.class));
    }

    /**
     * Reads a show's other titles.
     *
     * @param tvMazeId TVmaze's id
     * @return TVmaze's array
     */
    public JsonNode akas(int tvMazeId) {
        return call("akas " + tvMazeId,
                () -> http.get().uri("/shows/{id}/akas", tvMazeId).retrieve().body(JsonNode.class));
    }

    /**
     * Reads which shows changed lately.
     *
     * @param since {@code day}, {@code week} or {@code month}
     * @return TVmaze's id to the time of its last change, in seconds since the epoch
     */
    public Map<Integer, Long> updates(String since) {
        JsonNode map = call("updates " + since,
                () -> http.get().uri("/updates/shows?since={since}", since).retrieve().body(JsonNode.class));
        Map<Integer, Long> updates = new HashMap<>();
        if (map != null) {
            map.properties().forEach(e -> updates.put(Integer.valueOf(e.getKey()), e.getValue().asLong()));
        }
        return updates;
    }

    private Optional<JsonNode> optional(String what, Supplier<JsonNode> request) {
        try {
            return Optional.ofNullable(call(what, request));
        } catch (MetadataUnavailableException e) {
            if (e.getCause() instanceof HttpClientErrorException.NotFound) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private JsonNode call(String what, Supplier<JsonNode> request) {
        pace();
        try {
            return request.get();
        } catch (HttpClientErrorException.TooManyRequests e) {
            LOG.warn("TVmaze asked to slow down ({}), trying again in {} s", what, TOO_MANY_WAIT.toSeconds());
            sleep(TOO_MANY_WAIT.toMillis());
            try {
                return request.get();
            } catch (RestClientException again) {
                throw new MetadataUnavailableException("TVmaze: " + what, again);
            }
        } catch (RestClientException e) {
            throw new MetadataUnavailableException("TVmaze: " + what, e);
        }
    }

    private void pace() {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long start = Math.max(now, nextCall);
            nextCall = start + spacingNanos;
            wait = start - now;
        }
        if (wait > 0) {
            sleep(wait / 1_000_000);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataUnavailableException("interrupted", e);
        }
    }
}
