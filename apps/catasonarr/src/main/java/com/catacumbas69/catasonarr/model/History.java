package com.catacumbas69.catasonarr.model;

import com.catacumbas69.catasonarr.quality.QualityModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What happened to an episode, as Sonarr's history (orch.md decision 122): grabbed, imported, failed, deleted,
 * renamed, ignored. One per episode, as Sonarr does.
 *
 * @param id Sonarr-style id
 * @param episodeId the episode
 * @param seriesId the series
 * @param sourceTitle the release's or the file's name
 * @param quality the quality
 * @param languages the languages
 * @param date when
 * @param eventType {@code grabbed}, {@code downloadFolderImported}, {@code seriesFolderImported},
 *                  {@code downloadFailed}, {@code episodeFileDeleted}, {@code episodeFileRenamed} or
 *                  {@code downloadIgnored}
 * @param downloadId the download client's id of it (the torrent's hash), or {@code null}
 * @param data more, as Sonarr's: {@code indexer}, {@code size}, {@code downloadClient}, {@code message}, …
 */
@Document("catasonarr_history")
public record History(@Id int id, int episodeId, int seriesId, String sourceTitle, QualityModel quality,
                      List<Language> languages, Instant date, String eventType, String downloadId,
                      Map<String, String> data) {
}
