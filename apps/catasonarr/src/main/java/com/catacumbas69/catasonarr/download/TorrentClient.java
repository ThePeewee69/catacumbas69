package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.model.DownloadClient;
import java.util.List;

/** One kind of torrent client: qBittorrent or Transmission (orch.md decision 122). */
public interface TorrentClient {

    /**
     * The implementation it serves, as Sonarr names it.
     *
     * @return e.g. {@code QBittorrent}
     */
    String implementation();

    /**
     * Adds a torrent in Catasonarr's category.
     *
     * @param client the client
     * @param add what to add
     */
    void add(DownloadClient client, AddRequest add);

    /**
     * The torrents of Catasonarr's category.
     *
     * @param client the client
     * @return its downloads
     */
    List<ClientItem> items(DownloadClient client);

    /**
     * Removes a torrent.
     *
     * @param client the client
     * @param hash the torrent's hash
     * @param deleteData also delete its files
     */
    void remove(DownloadClient client, String hash, boolean deleteData);

    /**
     * Checks that the client answers and has Catasonarr's category.
     *
     * @param client the client
     */
    void test(DownloadClient client);

    /**
     * A torrent to add.
     *
     * @param hash its hash, upper case
     * @param magnet its magnet link, or {@code null} with a .torrent file
     * @param torrent its .torrent file, or {@code null} with a magnet link
     * @param name the release's name
     * @param recent whether its episodes aired in the last 14 days (Sonarr's recent priority)
     * @param seedRatio the indexer's seed ratio, or {@code null}
     * @param seedTimeMinutes the indexer's seed time, or {@code null}
     */
    record AddRequest(String hash, String magnet, byte[] torrent, String name, boolean recent, Double seedRatio,
                      Integer seedTimeMinutes) {
    }

    /** A client that could not do what was asked. */
    class ClientException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * A failure.
         *
         * @param message what went wrong
         */
        public ClientException(String message) {
            super(message);
        }
    }
}
