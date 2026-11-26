package com.catacumbas69.catasonarr.model;

import com.catacumbas69.catasonarr.quality.QualityModel;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A release not to grab again, as Sonarr's blocklist (orch.md decision 122): failed, or removed from the queue
 * with blocklist.
 *
 * @param id Sonarr-style id
 * @param seriesId the series
 * @param episodeIds its episodes
 * @param sourceTitle the release's name
 * @param quality its quality
 * @param languages its languages
 * @param date when it was blocked
 * @param publishedDate when it was published
 * @param size bytes
 * @param protocol {@code torrent}
 * @param indexer the indexer's name
 * @param indexerFlags its flags
 * @param releaseType Sonarr's release type number
 * @param message why
 * @param torrentInfoHash its hash, or {@code null}
 */
@Document("catasonarr_blocklist")
public record Blocklist(@Id int id, int seriesId, List<Integer> episodeIds, String sourceTitle, QualityModel quality,
                        List<Language> languages, Instant date, Instant publishedDate, Long size, String protocol,
                        String indexer, int indexerFlags, int releaseType, String message, String torrentInfoHash) {
}
