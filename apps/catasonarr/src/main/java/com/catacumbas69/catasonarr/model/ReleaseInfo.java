package com.catacumbas69.catasonarr.model;

import java.time.Instant;

/**
 * A release an indexer offers, as Sonarr's {@code TorrentInfo} (orch.md decision 122).
 *
 * @param guid the indexer's id of it
 * @param title its name
 * @param size bytes
 * @param downloadUrl where the .torrent (or magnet) is
 * @param magnetUrl its magnet link, or {@code null}
 * @param infoHash its torrent hash, or {@code null}
 * @param infoUrl its page on the tracker
 * @param indexerId the indexer
 * @param indexer the indexer's name
 * @param indexerPriority the indexer's priority
 * @param tvdbId TheTVDB's id the indexer gives, or 0
 * @param imdbId IMDb's id the indexer gives, or {@code null}
 * @param publishDate when it was published
 * @param seeders seeders, or {@code null}
 * @param peers peers, or {@code null}
 * @param indexerFlags freeleech and the like, as Sonarr's flags
 */
public record ReleaseInfo(String guid, String title, long size, String downloadUrl, String magnetUrl,
                          String infoHash, String infoUrl, int indexerId, String indexer, int indexerPriority,
                          int tvdbId, String imdbId, Instant publishDate, Integer seeders, Integer peers,
                          int indexerFlags) {

    /**
     * Its age in minutes.
     *
     * @param now the time
     * @return minutes since it was published
     */
    public double ageMinutes(Instant now) {
        return publishDate == null ? 0 : (now.toEpochMilli() - publishDate.toEpochMilli()) / 60000.0;
    }
}
