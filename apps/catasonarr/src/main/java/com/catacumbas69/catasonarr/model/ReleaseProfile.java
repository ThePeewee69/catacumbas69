package com.catacumbas69.catasonarr.model;

import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Terms a release must or must not have, as Sonarr's release profiles (orch.md decision 122). A term between
 * slashes is a regular expression ({@code /\bx265\b/i}).
 *
 * @param id Sonarr-style id
 * @param name its name
 * @param enabled whether it applies
 * @param required terms of which one must be there (none: no requirement)
 * @param ignored terms none of which may be there
 * @param indexerId only for this indexer's releases, or 0 for all
 * @param tags tag ids: only series with one of them (none: every series)
 */
@Document("catasonarr_releaseprofiles")
public record ReleaseProfile(@Id int id, String name, boolean enabled, List<String> required, List<String> ignored,
                             int indexerId, Set<Integer> tags) {
}
