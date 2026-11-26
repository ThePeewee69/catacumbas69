package com.catacumbas69.catasonarr.download;

/**
 * A download as its client reports it, as Sonarr's {@code DownloadClientItem}.
 *
 * @param clientId the client
 * @param clientName the client's name
 * @param downloadId the torrent's hash, upper case
 * @param title the torrent's name
 * @param category its category
 * @param totalSize bytes
 * @param remainingSize bytes left
 * @param remainingSeconds seconds left, or {@code null}
 * @param status {@code queued}, {@code paused}, {@code downloading}, {@code completed}, {@code failed} or
 *               {@code warning}
 * @param message what the client says, or {@code null}
 * @param outputPath where its files are, as Catasonarr sees it, or {@code null}
 * @param seedRatio its ratio
 */
public record ClientItem(int clientId, String clientName, String downloadId, String title, String category,
                         long totalSize, long remainingSize, Long remainingSeconds, String status, String message,
                         String outputPath, double seedRatio) {
}
