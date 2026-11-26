package com.catacumbas69.catasonarr.model;

import java.util.Map;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A download client, as Sonarr's {@code /api/v3/downloadclient} keeps it (orch.md decision 122): qBittorrent or
 * Transmission, with Catasonarr's own category ({@code catasonarr}), so it only touches what it sent.
 *
 * @param id Sonarr-style id
 * @param name e.g. {@code qBittorrent}
 * @param implementation {@code QBittorrent} or {@code Transmission}
 * @param settings its fields by name: {@code host}, {@code port}, {@code useSsl}, {@code urlBase},
 *                 {@code username}, {@code password}, {@code tvCategory}, …
 * @param enable whether it is used
 * @param priority 1 (first) to 50
 * @param removeCompletedDownloads remove the torrent once imported
 * @param removeFailedDownloads remove a failed torrent
 * @param tags tag ids: only series with one of them use it (none: every series)
 */
@Document("catasonarr_downloadclients")
public record DownloadClient(@Id int id, String name, String implementation, Map<String, Object> settings,
                             boolean enable, int priority, boolean removeCompletedDownloads,
                             boolean removeFailedDownloads, Set<Integer> tags) {
}
