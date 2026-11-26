package com.catacumbas69.catasonarr.model;

import java.util.Map;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An indexer, as Prowlarr pushes it through Sonarr's {@code /api/v3/indexer} (orch.md decision 122): a Torznab
 * feed of Prowlarr's for one tracker. Only Torznab (torrents); Prowlarr's usenet indexers are refused.
 *
 * @param id Sonarr-style id
 * @param name e.g. {@code 1337x (Prowlarr)}
 * @param implementation {@code Torznab}
 * @param settings its fields by name: {@code baseUrl}, {@code apiPath}, {@code apiKey}, {@code categories},
 *                 {@code animeCategories}, {@code minimumSeeders}, {@code seedCriteria.seedRatio}, …
 * @param enableRss read on every RSS sync
 * @param enableAutomaticSearch used by automatic searches
 * @param enableInteractiveSearch used by interactive searches
 * @param priority 1 (first) to 50
 * @param seasonSearchMaximumSingleEpisodeAge days after which a season search looks for single episodes
 * @param downloadClientId the client its releases go to, or 0 for any
 * @param tags tag ids: only series with one of them use it (none: every series)
 */
@Document("catasonarr_indexers")
public record Indexer(@Id int id, String name, String implementation, Map<String, Object> settings,
                      boolean enableRss, boolean enableAutomaticSearch, boolean enableInteractiveSearch,
                      int priority, int seasonSearchMaximumSingleEpisodeAge, int downloadClientId, Set<Integer> tags) {
}
