package com.catacumbas69.catasonarr.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A release waiting before it is grabbed (orch.md decision 122), as Sonarr's pending releases: held by a delay
 * profile, or because no download client could take it. Shown in the queue as {@code delay} or
 * {@code downloadClientUnavailable}.
 *
 * @param id Sonarr-style id
 * @param seriesId the series
 * @param episodeIds its episodes
 * @param title the release's name
 * @param added when it was first seen
 * @param release the release
 * @param reason {@code delay}, {@code downloadClientUnavailable} or {@code fallback}
 */
@Document("catasonarr_pendingreleases")
public record PendingRelease(@Id int id, int seriesId, List<Integer> episodeIds, String title, Instant added,
                             ReleaseInfo release, String reason) {
}
