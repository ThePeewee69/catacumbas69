package com.catacumbas69.catasonarr.model;

import com.catacumbas69.catasonarr.quality.QualityModel;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A video file of one or more episodes, in the series' folder (orch.md decision 122), as Sonarr's
 * {@code EpisodeFile}. Its episodes point at it through {@code episodeIds} (Sonarr keeps the id on the episode).
 *
 * @param id Sonarr-style id
 * @param seriesId the series
 * @param seasonNumber the season
 * @param episodeIds the episodes it holds
 * @param relativePath its path inside the series' folder, e.g. {@code Season 01/Show - S01E01.mkv}
 * @param size bytes
 * @param dateAdded when it was imported or found
 * @param sceneName the release's name it came from, or {@code null}
 * @param releaseGroup the release group
 * @param quality its quality
 * @param languages its languages
 * @param mediaInfo what ffprobe says, or {@code null}
 * @param originalFilePath where it was imported from, or {@code null}
 * @param indexerFlags the release's indexer flags (freeleech, …)
 * @param releaseType Sonarr's release type number
 */
@Document("catasonarr_episodefiles")
public record EpisodeFile(
        @Id int id,
        int seriesId,
        int seasonNumber,
        List<Integer> episodeIds,
        String relativePath,
        long size,
        Instant dateAdded,
        String sceneName,
        String releaseGroup,
        QualityModel quality,
        List<Language> languages,
        MediaInfo mediaInfo,
        String originalFilePath,
        int indexerFlags,
        int releaseType) {
}
