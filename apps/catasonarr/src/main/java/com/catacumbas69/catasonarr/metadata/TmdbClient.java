package com.catacumbas69.catasonarr.metadata;

import com.catacumbas69.catasonarr.config.CatasonarrProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * TMDB's API (orch.md decision 112): images and summaries in the configured language. Off when there is no token
 * ({@code CATASONARR_TMDB_TOKEN}, Secret {@code catasonarr-tmdb}); then TVmaze's poster and English summary stay.
 * "This product uses the TMDB API but is not endorsed or certified by TMDB" (on the page).
 */
@Component
public class TmdbClient {

    private final RestClient http;
    private final boolean enabled;

    /**
     * Creates the client.
     *
     * @param http the HTTP client for TMDB, with the token, from {@code CoreConfig}
     * @param properties whether there is a token
     */
    public TmdbClient(@Qualifier("tmdbRestClient") RestClient http, CatasonarrProperties properties) {
        this.http = http;
        this.enabled = StringUtils.hasText(properties.metadata().tmdbToken());
    }

    /**
     * Returns whether TMDB is used.
     *
     * @return {@code true} with a token
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Finds a series' TMDB id by TheTVDB's id.
     *
     * @param tvdbId TheTVDB's id
     * @return TMDB's id, or empty when TMDB does not know it or is off
     */
    public Optional<Integer> findByTvdb(int tvdbId) {
        if (!enabled || tvdbId <= 0) {
            return Optional.empty();
        }
        JsonNode found = get(() -> http.get().uri("/find/{id}?external_source=tvdb_id", tvdbId).retrieve()
                .body(JsonNode.class), "find tvdb " + tvdbId);
        if (found == null || found.path("tv_results").isEmpty()) {
            return Optional.empty();
        }
        int id = found.path("tv_results").path(0).path("id").asInt(0);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /**
     * Reads a series in a language.
     *
     * @param tmdbId TMDB's id
     * @param language e.g. {@code pt-PT}
     * @return TMDB's object, or empty when TMDB does not have it or is off
     */
    public Optional<JsonNode> tv(int tmdbId, String language) {
        if (!enabled || tmdbId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(get(() -> http.get().uri("/tv/{id}?language={language}", tmdbId, language)
                .retrieve().body(JsonNode.class), "tv " + tmdbId));
    }

    private static JsonNode get(Supplier<JsonNode> request, String what) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw new MetadataUnavailableException("TMDB: " + what, e);
        }
    }
}
