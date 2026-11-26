package com.catacumbas69.catasonarr.model;

import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * How long to wait for a better release before grabbing, as Sonarr's delay profiles (orch.md decision 122). The one
 * without tags (id 1, last in order) applies to every series no other one covers.
 *
 * @param id Sonarr-style id
 * @param enableUsenet usenet (always false: Catasonarr is torrents only)
 * @param enableTorrent torrents
 * @param preferredProtocol {@code torrent}
 * @param usenetDelay minutes (unused)
 * @param torrentDelay minutes to wait after a release first appears
 * @param order the order profiles are tried in; the default one is last
 * @param bypassIfHighestQuality grab at once when it is the profile's highest quality
 * @param bypassIfAboveCustomFormatScore grab at once when its custom format score reaches the minimum below
 * @param minimumCustomFormatScore that score
 * @param tags tag ids
 */
@Document("catasonarr_delayprofiles")
public record DelayProfile(@Id int id, boolean enableUsenet, boolean enableTorrent, String preferredProtocol,
                           int usenetDelay, int torrentDelay, int order, boolean bypassIfHighestQuality,
                           boolean bypassIfAboveCustomFormatScore, int minimumCustomFormatScore, Set<Integer> tags) {
}
