package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.decision.CustomFormatCalculator;
import com.catacumbas69.catasonarr.decision.Library;
import com.catacumbas69.catasonarr.decision.ProfileQualities;
import com.catacumbas69.catasonarr.decision.QueueView;
import com.catacumbas69.catasonarr.decision.ReleaseMatcher;
import com.catacumbas69.catasonarr.decision.RemoteEpisode;
import com.catacumbas69.catasonarr.indexers.AutomaticSearch;
import com.catacumbas69.catasonarr.media.EpisodeImporter;
import com.catacumbas69.catasonarr.model.DownloadClient;
import com.catacumbas69.catasonarr.model.History;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import com.catacumbas69.catasonarr.model.Settings;
import com.catacumbas69.catasonarr.parser.ParsedEpisodeInfo;
import com.catacumbas69.catasonarr.parser.Parser;
import com.catacumbas69.catasonarr.parser.ParserText;
import com.catacumbas69.catasonarr.service.BlocklistService;
import com.catacumbas69.catasonarr.service.CatasonarrEvents;
import com.catacumbas69.catasonarr.service.HistoryService;
import com.catacumbas69.catasonarr.service.NotFoundException;
import com.catacumbas69.catasonarr.service.SettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Follows Catasonarr's downloads (orch.md decision 122), as Sonarr's {@code DownloadMonitoringService},
 * {@code CompletedDownloadService} and {@code FailedDownloadService}: every minute each client's torrents in the
 * {@code catasonarr} category are read and matched to their grab (or, when added by hand, to a series by name).
 * A finished torrent is imported (moved and renamed) and then removed from its client; a failed one is
 * blocklisted, removed and searched for again. This is the queue the API and the decision engine see.
 */
@Service
public class TrackedDownloadService implements QueueView {

    private static final Logger LOG = LoggerFactory.getLogger(TrackedDownloadService.class);
    private static final Duration BLOCKED_RETRY = Duration.ofMinutes(10);

    private final DownloadClientService clients;
    private final HistoryService history;
    private final Library library;
    private final ReleaseMatcher matcher;
    private final CustomFormatCalculator formats;
    private final EpisodeImporter importer;
    private final BlocklistService blocklist;
    private final SettingsService settings;
    private final AutomaticSearch search;
    private final CatasonarrEvents events;
    private final Clock clock;
    private final Map<String, TrackedDownload> tracked = new ConcurrentHashMap<>();
    private final Set<Integer> unavailable = ConcurrentHashMap.newKeySet();
    private final ReentrantLock refreshing = new ReentrantLock();

    /**
     * Creates the service.
     *
     * @param clients the download clients
     * @param history the history
     * @param library the series and episodes
     * @param matcher maps names to series
     * @param formats custom formats
     * @param importer imports finished downloads
     * @param blocklist the blocklist
     * @param settings completed and failed download handling
     * @param search searches again after a failure (lazy: it needs this queue)
     * @param events events
     * @param clock the time
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public TrackedDownloadService(DownloadClientService clients, HistoryService history, Library library,
                                  ReleaseMatcher matcher, CustomFormatCalculator formats, EpisodeImporter importer,
                                  BlocklistService blocklist, SettingsService settings, @Lazy AutomaticSearch search,
                                  CatasonarrEvents events, Clock clock) {
        this.clients = clients;
        this.history = history;
        this.library = library;
        this.matcher = matcher;
        this.formats = formats;
        this.importer = importer;
        this.blocklist = blocklist;
        this.settings = settings;
        this.search = search;
        this.events = events;
        this.clock = clock;
    }

    /** Reads the clients and handles finished and failed downloads, every minute (Sonarr's interval). */
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT1M")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException e) {
            LOG.warn("Downloads not refreshed, tried again in a minute: {}", e.getMessage());
        }
    }

    /**
     * Reads the clients now ({@code RefreshMonitoredDownloads}); skipped when a refresh is running.
     *
     * @return whether it ran
     */
    public boolean refresh() {
        if (!refreshing.tryLock()) {
            return false;
        }
        try {
            Set<String> seen = new HashSet<>();
            for (DownloadClient client : clients.all()) {
                if (!client.enable()) {
                    continue;
                }
                List<ClientItem> items;
                try {
                    items = clients.implementation(client).items(client);
                    unavailable.remove(client.id());
                } catch (RuntimeException e) {
                    if (unavailable.add(client.id())) {
                        LOG.warn("Download client {} is unavailable: {}", client.name(), e.getMessage());
                    }
                    tracked.forEach((k, t) -> {
                        if (t.item.clientId() == client.id()) {
                            seen.add(k);
                        }
                    });
                    continue;
                }
                for (ClientItem item : items) {
                    String key = key(item.clientId(), item.downloadId());
                    seen.add(key);
                    TrackedDownload t = tracked.computeIfAbsent(key, k -> {
                        TrackedDownload created = new TrackedDownload(item);
                        map(created);
                        return created;
                    });
                    t.item = item;
                    process(t, client);
                }
            }
            tracked.keySet().retainAll(seen);
            return true;
        } finally {
            refreshing.unlock();
        }
    }

    /**
     * Whether a client could not be read at the last refresh.
     *
     * @param clientId the client
     * @return whether it is unavailable
     */
    public boolean unavailable(int clientId) {
        return unavailable.contains(clientId);
    }

    /**
     * The downloads followed, oldest first; imported ones are left out.
     *
     * @return the downloads
     */
    public List<TrackedDownload> all() {
        return tracked.values().stream().filter(t -> !TrackedDownload.IMPORTED.equals(t.state))
                .sorted(Comparator.comparing((TrackedDownload t) -> t.added == null ? Instant.EPOCH : t.added))
                .toList();
    }

    /**
     * A download by its hash.
     *
     * @param downloadId the hash
     * @return the download, if followed
     */
    public Optional<TrackedDownload> find(String downloadId) {
        return tracked.values().stream().filter(t -> t.item.downloadId().equalsIgnoreCase(downloadId)).findFirst();
    }

    @Override
    public List<Queued> queued() {
        return tracked.values().stream().filter(t -> t.series != null)
                .filter(t -> !TrackedDownload.IMPORTED.equals(t.state) && !TrackedDownload.FAILED.equals(t.state))
                .map(t -> new Queued(t.series.id(), t.episodeIds(), t.quality, t.customFormats,
                        TrackedDownload.FAILED_PENDING.equals(t.state)))
                .toList();
    }

    /**
     * Removes a download from the queue (Sonarr's {@code DELETE /api/v3/queue/{id}}).
     *
     * @param downloadId the hash
     * @param removeFromClient also remove the torrent and its data from the client
     * @param addToBlocklist blocklist the release (and search again unless {@code skipRedownload})
     * @param skipRedownload do not search again
     * @param actor who asked
     */
    public void remove(String downloadId, boolean removeFromClient, boolean addToBlocklist, boolean skipRedownload,
                       String actor) {
        TrackedDownload t = find(downloadId).orElseThrow(() -> new NotFoundException("Download " + downloadId));
        DownloadClient client = clients.find(t.item.clientId()).orElse(null);
        if (removeFromClient && client != null) {
            clients.implementation(client).remove(client, t.item.downloadId(), true);
        }
        if (addToBlocklist) {
            failed(t, client, "Manually marked as failed", skipRedownload, false, actor);
        }
        tracked.remove(key(t.item.clientId(), t.item.downloadId()));
        events.action("queue", "removed", t.item.title(), actor, Map.of("downloadId", t.item.downloadId(),
                "removeFromClient", removeFromClient, "blocklist", addToBlocklist, "skipRedownload", skipRedownload));
    }

    /**
     * Marks a grab as failed (Sonarr's {@code POST /api/v3/history/failed/{id}}): blocklisted and searched again,
     * removed from its client when still there.
     *
     * @param downloadId the grab's hash
     * @param actor who asked
     */
    public void markFailed(String downloadId, String actor) {
        Optional<TrackedDownload> found = find(downloadId);
        if (found.isPresent()) {
            remove(downloadId, true, true, false, actor);
            return;
        }
        List<History> grabs = history.grabbed(downloadId);
        if (grabs.isEmpty()) {
            throw new NotFoundException("Grab " + downloadId);
        }
        History grab = grabs.get(0);
        long size = grab.data() == null ? 0 : integer(grab.data().get("size"));
        TrackedDownload t = new TrackedDownload(new ClientItem(0, null, downloadId.toUpperCase(java.util.Locale.ROOT),
                grab.sourceTitle(), null, size, 0, null, "failed", null, null, 0));
        map(t);
        failed(t, null, "Manually marked as failed", false, false, actor);
    }

    private void map(TrackedDownload t) {
        String hash = t.item.downloadId();
        List<History> grabs = history.grabbed(hash);
        if (!grabs.isEmpty()) {
            History grab = grabs.stream().max(Comparator.comparing(History::date)).orElseThrow();
            t.grabbed = true;
            t.series = library.series(grab.seriesId()).orElse(null);
            t.episodes = library.episodes(grabs.stream().map(History::episodeId).distinct().toList());
            t.quality = grab.quality();
            t.languages = grab.languages() == null ? List.of() : grab.languages();
            t.indexer = grab.data() == null ? null : grab.data().get("indexer");
            t.added = grab.date();
        } else {
            ParsedEpisodeInfo parsed = Parser.parseTitle(t.item.title());
            RemoteEpisode remote = parsed == null ? null : matcher.map(parsed, 0, null, null);
            if (remote != null && remote.series != null && !remote.episodes.isEmpty()) {
                t.series = remote.series;
                t.episodes = remote.episodes;
                t.quality = parsed.quality;
                t.languages = remote.languages;
            }
            t.added = clock.instant();
        }
        if (t.series == null) {
            t.warn("Unknown series: the download could not be matched to a series in the library");
            return;
        }
        t.customFormats = formats.forTitle(t.item.title(), t.quality, t.languages, t.item.totalSize(), t.series);
        t.customFormatScore = ProfileQualities.score(library.profile(t.series), t.customFormats);
    }

    private void process(TrackedDownload t, DownloadClient client) {
        if (TrackedDownload.IMPORTED.equals(t.state) || TrackedDownload.FAILED.equals(t.state)) {
            return;
        }
        Settings s = settings.get();
        if ("failed".equals(t.item.status())) {
            if (!t.grabbed) {
                t.warn("Download wasn't grabbed by Catasonarr, skipping");
                return;
            }
            failed(t, client, t.item.message() == null ? "The download client reported a failure"
                    : t.item.message(), false, true, CatasonarrEvents.SELF);
            return;
        }
        if (!"completed".equals(t.item.status()) || !s.enableCompletedDownloadHandling()) {
            if (t.series != null && !TrackedDownload.IMPORT_BLOCKED.equals(t.state)) {
                t.status(TrackedDownload.OK, new ArrayList<>());
            }
            return;
        }
        if (TrackedDownload.IMPORT_BLOCKED.equals(t.state) && t.lastImport != null
                && t.lastImport.plus(BLOCKED_RETRY).isAfter(clock.instant())) {
            return;
        }
        complete(t, client);
    }

    /** Sonarr's {@code CompletedDownloadService.Check} and {@code Import}. */
    private void complete(TrackedDownload t, DownloadClient client) {
        t.lastImport = clock.instant();
        if (t.series == null) {
            t.state = TrackedDownload.IMPORT_BLOCKED;
            return;
        }
        Set<Integer> before = importedBefore(t);
        if (!before.isEmpty() && before.containsAll(t.episodeIds())) {
            t.state = TrackedDownload.IMPORTED;
            t.status(TrackedDownload.OK, new ArrayList<>());
            removeImported(t, client);
            return;
        }
        String output = t.item.outputPath();
        if (ParserText.isBlank(output)) {
            t.state = TrackedDownload.IMPORT_BLOCKED;
            t.warn("Download doesn't contain intermediate path, Skipping.");
            return;
        }
        Path path = Path.of(output);
        if (!Files.exists(path)) {
            t.state = TrackedDownload.IMPORT_BLOCKED;
            t.warn("Import failed, path does not exist or is not accessible by Catasonarr: " + output
                    + ". Ensure the path is mapped correctly.");
            return;
        }
        t.state = TrackedDownload.IMPORTING;
        List<EpisodeImporter.Outcome> outcomes;
        try {
            outcomes = importer.importDownload(t.series, path, new EpisodeImporter.Download(t.item.downloadId(),
                    t.item.title(), client.implementation(), client.name()), CatasonarrEvents.SELF);
        } catch (RuntimeException e) {
            LOG.warn("Unable to import {}: {}", t.item.title(), e.getMessage());
            t.state = TrackedDownload.IMPORT_BLOCKED;
            t.status(TrackedDownload.ERROR, List.of(new TrackedDownload.StatusMessage(t.item.title(),
                    List.of("Import failed: " + e.getMessage()))));
            return;
        }
        Set<Integer> imported = importedBefore(t);
        outcomes.stream().filter(EpisodeImporter.Outcome::imported).forEach(o -> imported.addAll(o.episodeIds()));
        boolean everyFileDone = !outcomes.isEmpty() && outcomes.stream().allMatch(o -> o.imported() || o.sample()
                || o.rejection() != null && o.rejection().startsWith("Episode file already imported"));
        boolean all = !imported.isEmpty() && (imported.containsAll(t.episodeIds()) || everyFileDone);
        if (all) {
            t.state = TrackedDownload.IMPORTED;
            t.status(TrackedDownload.OK, new ArrayList<>());
            removeImported(t, client);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("seriesId", t.series.id());
            details.put("episodeIds", new ArrayList<>(imported));
            details.put("downloadId", t.item.downloadId());
            details.put("downloadClient", client.name());
            details.put("removed", client.removeCompletedDownloads());
            events.action("download", "imported", t.item.title(), CatasonarrEvents.SELF, details);
            return;
        }
        t.state = TrackedDownload.IMPORT_BLOCKED;
        List<TrackedDownload.StatusMessage> messages = new ArrayList<>();
        if (outcomes.isEmpty()) {
            messages.add(new TrackedDownload.StatusMessage(t.item.title(),
                    List.of("No files found are eligible for import in " + output)));
        }
        for (EpisodeImporter.Outcome o : outcomes) {
            if (!o.imported() && !o.sample()) {
                messages.add(new TrackedDownload.StatusMessage(o.path().getFileName().toString(),
                        List.of(o.rejection())));
            }
        }
        if (messages.isEmpty()) {
            messages.add(new TrackedDownload.StatusMessage(t.item.title(), List.of("One or more episodes expected"
                    + " in this release were not imported or missing from the release")));
        }
        t.status(TrackedDownload.WARNING, messages);
    }

    /** The episodes already imported from this download since it was grabbed (Sonarr's import history). */
    private Set<Integer> importedBefore(TrackedDownload t) {
        Instant grabbedAt = t.added == null ? Instant.EPOCH : t.added;
        Set<Integer> imported = new HashSet<>();
        history.byDownloadId(t.item.downloadId()).stream()
                .filter(h -> HistoryService.DOWNLOAD_FOLDER_IMPORTED.equals(h.eventType()))
                .filter(h -> !h.date().isBefore(grabbedAt)).forEach(h -> imported.add(h.episodeId()));
        return imported;
    }

    /** Removes an imported torrent and its data from its client, when the client says so. */
    private void removeImported(TrackedDownload t, DownloadClient client) {
        if (!client.removeCompletedDownloads()) {
            return;
        }
        try {
            clients.implementation(client).remove(client, t.item.downloadId(), true);
        } catch (RuntimeException e) {
            LOG.warn("Imported {}, but unable to remove it from {}: {}", t.item.title(), client.name(),
                    e.getMessage());
        }
    }

    /** Sonarr's {@code FailedDownloadService}: history, blocklist, removal and a new search. */
    private void failed(TrackedDownload t, DownloadClient client, String message, boolean skipRedownload,
                        boolean removeFromClient, String actor) {
        t.state = TrackedDownload.FAILED_PENDING;
        List<History> grabs = history.grabbed(t.item.downloadId());
        Map<String, String> grab = grabs.stream().map(History::data).filter(Objects::nonNull).findFirst()
                .orElse(Map.of());
        if (t.series != null) {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("message", message);
            data.put("downloadClient", client == null ? null : client.implementation());
            data.put("downloadClientName", client == null ? null : client.name());
            data.put("indexer", grab.get("indexer"));
            data.put("releaseGroup", grab.get("releaseGroup"));
            data.put("size", String.valueOf(t.item.totalSize()));
            history.record(HistoryService.DOWNLOAD_FAILED, t.series.id(), t.episodeIds(), t.item.title(), t.quality,
                    t.languages, t.item.downloadId(), data);
            ReleaseInfo release = new ReleaseInfo(grab.get("guid"), grabs.isEmpty() ? t.item.title()
                    : grabs.get(0).sourceTitle(), t.item.totalSize(), grab.get("downloadUrl"), null,
                    t.item.downloadId(), grab.get("nzbInfoUrl"), 0, grab.get("indexer"), 0, 0, grab.get("imdbId"),
                    grab.get("publishedDate") == null ? null : Instant.parse(grab.get("publishedDate")), null, null,
                    integer(grab.get("indexerFlags")));
            blocklist.block(t.series.id(), t.episodeIds(), release, t.quality, t.languages,
                    integer(grab.get("releaseType")), message, actor);
        }
        if (removeFromClient && client != null && client.removeFailedDownloads()) {
            try {
                clients.implementation(client).remove(client, t.item.downloadId(), true);
            } catch (RuntimeException e) {
                LOG.warn("Unable to remove the failed {} from {}: {}", t.item.title(), client.name(), e.getMessage());
            }
        }
        t.state = TrackedDownload.FAILED;
        t.status(TrackedDownload.ERROR, List.of(new TrackedDownload.StatusMessage(t.item.title(), List.of(message))));
        events.action("download", "failed", t.item.title(), actor, Map.of("downloadId", t.item.downloadId(),
                "message", message));
        LOG.info("Download {} failed: {}", t.item.title(), message);
        Settings s = settings.get();
        boolean interactive = "InteractiveSearch".equals(grab.get("releaseSource"));
        if (skipRedownload || t.series == null || t.episodes.isEmpty() || !s.autoRedownloadFailed()
                || interactive && !s.autoRedownloadFailedFromInteractiveSearch()) {
            return;
        }
        int seriesId = t.series.id();
        List<Integer> ids = t.episodeIds();
        ParsedEpisodeInfo parsed = Parser.parseTitle(t.item.title());
        boolean season = parsed != null && parsed.fullSeason;
        int seasonNumber = t.episodes.get(0).seasonNumber();
        Thread.ofVirtual().name("redownload").start(() -> {
            try {
                AutomaticSearch.Result r = season ? search.seasonSearch(seriesId, seasonNumber, CatasonarrEvents.SELF)
                        : search.episodeSearch(ids, CatasonarrEvents.SELF);
                LOG.info("Searched again after the failure of {}: {}", t.item.title(), r == null ? "" : r.message());
            } catch (RuntimeException e) {
                LOG.warn("Unable to search again after {}: {}", t.item.title(), e.getMessage());
            }
        });
    }

    private static String key(int clientId, String hash) {
        return clientId + ":" + hash.toUpperCase(java.util.Locale.ROOT);
    }

    private static int integer(String s) {
        Integer n = ParserText.tryParseInt(s);
        return n == null ? 0 : n;
    }
}
