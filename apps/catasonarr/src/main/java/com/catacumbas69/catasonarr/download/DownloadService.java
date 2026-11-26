package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.decision.RemoteEpisode;
import com.catacumbas69.catasonarr.indexers.IndexerService;
import com.catacumbas69.catasonarr.model.DownloadClient;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.Indexer;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import com.catacumbas69.catasonarr.service.CatasonarrEvents;
import com.catacumbas69.catasonarr.service.HistoryService;
import com.catacumbas69.catasonarr.service.PendingReleaseService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Grabs a release (orch.md decision 122), as Sonarr's {@code DownloadService} and {@code TorrentClientBase}: picks
 * the client, fetches the .torrent (or follows Prowlarr's redirect to a magnet link), adds it in Catasonarr's
 * category, and records the grab in the history with the torrent's hash, which is how the download is followed.
 */
@Service
public class DownloadService {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadService.class);

    /** No client could take the release: it waits, as Sonarr's pending reason. */
    public static class NoClientException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * No client.
         *
         * @param message why
         */
        public NoClientException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final DownloadClientService clients;
    private final IndexerService indexers;
    private final HistoryService history;
    private final PendingReleaseService pending;
    private final CatasonarrEvents events;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param clients the download clients
     * @param indexers the indexers
     * @param history the history
     * @param pending held-back releases
     * @param events events
     * @param clock the time
     */
    public DownloadService(DownloadClientService clients, IndexerService indexers, HistoryService history,
                           PendingReleaseService pending, CatasonarrEvents events, Clock clock) {
        this.clients = clients;
        this.indexers = indexers;
        this.history = history;
        this.pending = pending;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Grabs a release.
     *
     * @param remote the release with its series and episodes
     * @param actor who grabbed it ({@code catasonarr} for automatic grabs)
     * @return the download's id (the torrent's hash)
     * @throws NoClientException when no client is enabled for the series
     */
    public String grab(RemoteEpisode remote, String actor) {
        ReleaseInfo release = remote.release;
        Optional<Indexer> indexer = release.indexerId() == 0 ? Optional.empty() : indexers.find(release.indexerId());
        int indexerClient = indexer.map(Indexer::downloadClientId).orElse(0);
        DownloadClient client = clients.pick(remote.series.tags() == null ? java.util.Set.of() : remote.series.tags(),
                indexerClient).orElseThrow(() -> new NoClientException("No download client is available"));
        TorrentClient impl = clients.implementation(client);

        Torrent torrent = fetch(release);
        Instant recentLimit = clock.instant().minus(14, ChronoUnit.DAYS);
        boolean recent = remote.episodes.stream().anyMatch(e -> e.airDateUtc() != null
                && e.airDateUtc().isAfter(recentLimit));
        Double ratio = indexer.map(i -> decimal(i.settings().get("seedCriteria.seedRatio"))).orElse(null);
        Integer seedTime = indexer.map(i -> integer(i.settings().get(remote.parsed.fullSeason
                ? "seedCriteria.seasonPackSeedTime" : "seedCriteria.seedTime"))).orElse(null);
        impl.add(client, new TorrentClient.AddRequest(torrent.hash(), torrent.magnet(), torrent.file(),
                release.title(), recent, ratio, seedTime));

        Map<String, String> data = new LinkedHashMap<>();
        data.put("indexer", release.indexer());
        data.put("nzbInfoUrl", release.infoUrl());
        data.put("releaseGroup", remote.parsed.releaseGroup);
        data.put("age", String.valueOf((int) (release.ageMinutes(clock.instant()) / 1440)));
        data.put("ageHours", String.valueOf(release.ageMinutes(clock.instant()) / 60));
        data.put("ageMinutes", String.valueOf(release.ageMinutes(clock.instant())));
        data.put("publishedDate", release.publishDate() == null ? null : release.publishDate().toString());
        data.put("downloadClient", client.implementation());
        data.put("downloadClientName", client.name());
        data.put("size", String.valueOf(release.size()));
        data.put("downloadUrl", release.downloadUrl());
        data.put("guid", release.guid());
        data.put("tvdbId", String.valueOf(release.tvdbId()));
        data.put("imdbId", release.imdbId());
        data.put("protocol", "2");
        data.put("customFormatScore", String.valueOf(remote.customFormatScore));
        data.put("releaseSource", remote.releaseSource);
        data.put("indexerFlags", String.valueOf(release.indexerFlags()));
        data.put("releaseType", String.valueOf(remote.parsed.releaseType().value()));
        data.put("torrentInfoHash", torrent.hash());
        history.record(HistoryService.GRABBED, remote.series.id(), remote.episodeIds(), release.title(),
                remote.parsed.quality, remote.languages, torrent.hash(), data);
        pending.removeForEpisodes(remote.series.id(), remote.episodeIds());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("seriesId", remote.series.id());
        details.put("episodeIds", remote.episodeIds());
        details.put("quality", remote.parsed.quality.toString());
        details.put("indexer", release.indexer());
        details.put("downloadClient", client.name());
        details.put("downloadId", torrent.hash());
        details.put("size", release.size());
        details.put("customFormatScore", remote.customFormatScore);
        details.put("releaseSource", remote.releaseSource);
        events.action("release", "grabbed", release.title(), actor, details);
        LOG.info("Grabbed {} for {} {} with {} (by {})", release.title(), remote.series.title(),
                remote.episodes.stream().map(DownloadService::sxe).toList(), client.name(), actor);
        return torrent.hash();
    }

    private record Torrent(String hash, String magnet, byte[] file) {
    }

    /** The .torrent from the release's link, or the magnet link it redirects to or the indexer gives. */
    private Torrent fetch(ReleaseInfo release) {
        String url = release.downloadUrl();
        if (url != null && url.startsWith("magnet:")) {
            return magnet(url);
        }
        for (int hop = 0; url != null && hop < 5; hop++) {
            HttpResponse<byte[]> response;
            try {
                response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "Catasonarr").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (release.magnetUrl() != null) {
                    return magnet(release.magnetUrl());
                }
                throw new TorrentClient.ClientException("Unable to fetch the torrent: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TorrentClient.ClientException("Interrupted");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location != null && location.startsWith("magnet:")) {
                    return magnet(location);
                }
                url = location == null ? null : URI.create(url).resolve(location).toString();
                continue;
            }
            if (status >= 400) {
                if (release.magnetUrl() != null) {
                    return magnet(release.magnetUrl());
                }
                throw new TorrentClient.ClientException("The indexer answered HTTP " + status + " for the torrent");
            }
            try {
                return new Torrent(TorrentFiles.torrentHash(response.body()), null, response.body());
            } catch (IllegalArgumentException e) {
                if (release.magnetUrl() != null) {
                    return magnet(release.magnetUrl());
                }
                throw new TorrentClient.ClientException("The indexer did not send a .torrent file");
            }
        }
        if (release.magnetUrl() != null) {
            return magnet(release.magnetUrl());
        }
        throw new TorrentClient.ClientException("The release has no download link");
    }

    private static Torrent magnet(String magnet) {
        String hash = TorrentFiles.magnetHash(magnet);
        if (hash == null) {
            throw new TorrentClient.ClientException("The magnet link has no hash");
        }
        return new Torrent(hash, magnet, null);
    }

    private static String sxe(Episode e) {
        return String.format("S%02dE%02d", e.seasonNumber(), e.episodeNumber());
    }

    private static Double decimal(Object o) {
        if (o == null || o.toString().isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(Object o) {
        Double d = decimal(o);
        return d == null ? null : d.intValue();
    }
}
