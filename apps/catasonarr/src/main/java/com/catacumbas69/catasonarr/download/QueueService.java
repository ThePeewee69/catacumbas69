package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.controller.SonarrResources;
import com.catacumbas69.catasonarr.decision.CustomFormatCalculator;
import com.catacumbas69.catasonarr.decision.Library;
import com.catacumbas69.catasonarr.decision.ProfileQualities;
import com.catacumbas69.catasonarr.decision.ReleaseMatcher;
import com.catacumbas69.catasonarr.decision.RemoteEpisode;
import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.DelayProfile;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.PendingRelease;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.parser.ParsedEpisodeInfo;
import com.catacumbas69.catasonarr.quality.QualityModel;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.EpisodeFileService;
import com.catacumbas69.catasonarr.service.NotFoundException;
import com.catacumbas69.catasonarr.service.PendingReleaseService;
import com.catacumbas69.catasonarr.service.ProfileService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Sonarr's queue (orch.md decision 122): one item per episode of each download followed and of each release held
 * back (delay profile, or no client), with Sonarr's ids, states, statuses and messages, for {@code /api/v3/queue}
 * and the Activity page.
 */
@Service
public class QueueService {

    /**
     * One queue item and what it points at.
     *
     * @param resource the API's resource
     * @param downloadId the download's hash, or {@code null} for a held-back release
     * @param pendingId the held-back release, or 0
     * @param known whether its series is known
     */
    public record Item(Map<String, Object> resource, String downloadId, int pendingId, boolean known) {

        /**
         * The item's id.
         *
         * @return the id
         */
        public int id() {
            return (int) resource.get("id");
        }
    }

    private final TrackedDownloadService tracked;
    private final PendingReleaseService pending;
    private final Library library;
    private final EpisodeFileService files;
    private final ProfileService profiles;
    private final ReleaseMatcher matcher;
    private final CustomFormatCalculator formats;
    private final DownloadService downloads;
    private final DownloadClientService clients;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param tracked the downloads followed
     * @param pending held-back releases
     * @param library the series and episodes
     * @param files the episodes' files
     * @param profiles delay profiles (when a held-back release is grabbed)
     * @param matcher maps a held-back release again
     * @param formats custom formats
     * @param downloads grabs a held-back release
     * @param clients the download clients' names
     * @param clock the time
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public QueueService(TrackedDownloadService tracked, PendingReleaseService pending, Library library,
                        EpisodeFileService files, ProfileService profiles, ReleaseMatcher matcher,
                        CustomFormatCalculator formats, DownloadService downloads, DownloadClientService clients,
                        Clock clock) {
        this.tracked = tracked;
        this.pending = pending;
        this.library = library;
        this.files = files;
        this.profiles = profiles;
        this.matcher = matcher;
        this.formats = formats;
        this.downloads = downloads;
        this.clients = clients;
        this.clock = clock;
    }

    /**
     * Every queue item.
     *
     * @param includeSeries add each item's series
     * @param includeEpisode add each item's episode
     * @param includeUnknown add downloads that could not be matched to a series
     * @return the items, oldest first
     */
    public List<Item> items(boolean includeSeries, boolean includeEpisode, boolean includeUnknown) {
        Instant now = clock.instant();
        List<Item> items = new ArrayList<>();
        Map<Integer, Object> seriesResources = new HashMap<>();
        Function<Series, Object> seriesResource = s -> seriesResources.computeIfAbsent(s.id(),
                id -> SonarrResources.series(s, null));
        Map<Integer, EpisodeFile> fileByEpisode = new HashMap<>();
        List<Integer> seriesIds = new ArrayList<>();
        tracked.all().stream().filter(t -> t.series != null).forEach(t -> seriesIds.add(t.series.id()));
        List<PendingRelease> held = pending.all();
        held.forEach(p -> seriesIds.add(p.seriesId()));
        if (!seriesIds.isEmpty()) {
            fileByEpisode.putAll(files.byEpisode(seriesIds.stream().distinct().toList()));
        }

        for (TrackedDownload t : tracked.all()) {
            if (t.series == null || t.episodes.isEmpty()) {
                if (includeUnknown) {
                    Map<String, Object> r = download(t, null, null, now);
                    r.put("id", hash("trackedDownload-" + t.item.clientId() + "-" + t.item.downloadId()));
                    items.add(new Item(r, t.item.downloadId(), 0, false));
                }
                continue;
            }
            for (Episode e : t.episodes) {
                Map<String, Object> r = download(t, e, fileByEpisode.get(e.id()), now);
                r.put("id", hash("trackedDownload-" + t.item.clientId() + "-" + t.item.downloadId() + "-ep" + e.id()));
                if (includeSeries) {
                    r.put("series", seriesResource.apply(t.series));
                }
                if (includeEpisode) {
                    r.put("episode", episode(e, fileByEpisode.get(e.id())));
                }
                items.add(new Item(r, t.item.downloadId(), 0, true));
            }
        }

        for (PendingRelease p : held) {
            Optional<Series> series = library.series(p.seriesId());
            if (series.isEmpty()) {
                continue;
            }
            DelayProfile delay = profiles.bestForTags(series.get().tags() == null ? Set.of() : series.get().tags());
            Instant release = p.added().plus(Duration.ofMinutes(delay == null ? 0 : delay.torrentDelay()));
            ParsedEpisodeInfo parsed = matcher.parse(p.release(), null);
            QualityModel quality = parsed == null ? null : parsed.quality;
            List<Language> languages = parsed == null ? List.of() : parsed.languages;
            List<CustomFormat> cfs = parsed == null ? List.of()
                    : formats.forTitle(p.title(), quality, languages, p.release().size(), series.get());
            for (Episode e : library.episodes(p.episodeIds())) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", hash("pending-" + p.id() + "-ep" + e.id()));
                r.put("seriesId", p.seriesId());
                r.put("episodeId", e.id());
                r.put("seasonNumber", e.seasonNumber());
                if (includeSeries) {
                    r.put("series", seriesResource.apply(series.get()));
                }
                if (includeEpisode) {
                    r.put("episode", episode(e, fileByEpisode.get(e.id())));
                }
                r.put("languages", languages);
                r.put("quality", quality(quality));
                r.put("customFormats", cfs.stream().map(c -> Map.of("id", c.id(), "name", c.name())).toList());
                r.put("customFormatScore", ProfileQualities.score(library.profile(series.get()), cfs));
                r.put("size", p.release().size());
                r.put("title", p.title());
                r.put("sizeleft", p.release().size());
                Duration left = Duration.between(now, release);
                r.put("timeleft", left.isNegative() ? null : timeSpan(left));
                r.put("estimatedCompletionTime", release);
                r.put("added", p.added());
                r.put("status", p.reason());
                r.put("trackedDownloadStatus", "ok");
                r.put("trackedDownloadState", "downloading");
                r.put("statusMessages", List.of());
                r.put("protocol", "torrent");
                r.put("downloadClientHasPostImportCategory", false);
                r.put("indexer", p.release().indexer());
                r.put("episodeHasFile", fileByEpisode.containsKey(e.id()));
                items.add(new Item(r, null, p.id(), true));
            }
        }
        items.sort(Comparator.comparing(i -> String.valueOf(i.resource().get("added"))));
        return items;
    }

    /**
     * Sorts items as Sonarr's queue page does.
     *
     * @param items the items
     * @param sortKey what to sort by
     * @param ascending the direction
     * @return the items, sorted
     */
    public static List<Item> sort(List<Item> items, String sortKey, boolean ascending) {
        Comparator<Item> by = switch (sortKey == null ? "" : sortKey.toLowerCase(Locale.ROOT)) {
            case "title" -> Comparator.comparing(i -> String.valueOf(i.resource().get("title")),
                    String.CASE_INSENSITIVE_ORDER);
            case "series.sorttitle" -> Comparator.comparing(i -> sortTitle(i), String.CASE_INSENSITIVE_ORDER);
            case "status" -> Comparator.comparing(i -> String.valueOf(i.resource().get("status")));
            case "added" -> Comparator.comparing(i -> String.valueOf(i.resource().get("added")));
            case "size" -> Comparator.comparingLong(i -> ((Number) i.resource().get("size")).longValue());
            case "progress" -> Comparator.comparingDouble(QueueService::progress);
            case "episode", "episode.airdateutc" -> Comparator.comparing(i -> i.resource().get("seasonNumber") + "-"
                    + String.format("%09d", (int) i.resource().getOrDefault("episodeId", 0)));
            default -> Comparator.comparingLong(QueueService::secondsLeft);
        };
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(ascending ? by : by.reversed());
        return sorted;
    }

    /**
     * Removes queue items (Sonarr's {@code DELETE /api/v3/queue/{id}} and {@code /bulk}).
     *
     * @param ids the items
     * @param removeFromClient remove the torrents and their data from the client
     * @param blocklist blocklist the releases (and search again unless {@code skipRedownload})
     * @param skipRedownload do not search again
     * @param actor who asked
     */
    public void remove(List<Integer> ids, boolean removeFromClient, boolean blocklist, boolean skipRedownload,
                       String actor) {
        List<Item> all = items(false, false, true);
        List<String> downloadsDone = new ArrayList<>();
        List<Integer> pendingDone = new ArrayList<>();
        for (int id : ids) {
            Item item = all.stream().filter(i -> i.id() == id).findFirst()
                    .orElseThrow(() -> new NotFoundException("Queue item " + id));
            if (item.downloadId() != null && !downloadsDone.contains(item.downloadId())) {
                tracked.remove(item.downloadId(), removeFromClient, blocklist, skipRedownload, actor);
                downloadsDone.add(item.downloadId());
            } else if (item.pendingId() > 0 && !pendingDone.contains(item.pendingId())) {
                pending.remove(List.of(item.pendingId()));
                pendingDone.add(item.pendingId());
            }
        }
    }

    /**
     * Grabs held-back releases now (Sonarr's {@code POST /api/v3/queue/grab/{id}}).
     *
     * @param ids the items
     * @param actor who asked
     */
    public void grab(List<Integer> ids, String actor) {
        List<Item> all = items(false, false, false);
        List<Integer> done = new ArrayList<>();
        for (int id : ids) {
            Item item = all.stream().filter(i -> i.id() == id).findFirst()
                    .orElseThrow(() -> new NotFoundException("Queue item " + id));
            if (item.pendingId() == 0) {
                throw new BadRequestException("id", "Only a held-back release can be grabbed");
            }
            if (done.contains(item.pendingId())) {
                continue;
            }
            PendingRelease p = pending.get(item.pendingId());
            ParsedEpisodeInfo parsed = matcher.parse(p.release(), null);
            if (parsed == null) {
                throw new BadRequestException("id", "The release's name can no longer be parsed");
            }
            RemoteEpisode remote = matcher.map(parsed, p.seriesId(), p.episodeIds());
            remote.release = p.release();
            remote.customFormats = formats.forRelease(remote);
            remote.customFormatScore = ProfileQualities.score(library.profile(remote.series), remote.customFormats);
            remote.releaseSource = "Unknown";
            downloads.grab(remote, actor);
            pending.remove(List.of(p.id()));
            done.add(p.id());
        }
    }

    /**
     * Counts for {@code /api/v3/queue/status}.
     *
     * @return totalCount, count, unknownCount, errors, warnings, unknownErrors, unknownWarnings
     */
    public Map<String, Object> status() {
        List<Item> all = items(false, false, true);
        Map<String, Object> s = new LinkedHashMap<>();
        long known = all.stream().filter(Item::known).count();
        s.put("totalCount", all.size());
        s.put("count", known);
        s.put("unknownCount", all.size() - known);
        s.put("errors", all.stream().anyMatch(i -> i.known() && "error".equals(i.resource()
                .get("trackedDownloadStatus"))));
        s.put("warnings", all.stream().anyMatch(i -> i.known() && "warning".equals(i.resource()
                .get("trackedDownloadStatus"))));
        s.put("unknownErrors", all.stream().anyMatch(i -> !i.known() && "error".equals(i.resource()
                .get("trackedDownloadStatus"))));
        s.put("unknownWarnings", all.stream().anyMatch(i -> !i.known() && "warning".equals(i.resource()
                .get("trackedDownloadStatus"))));
        return s;
    }

    private Map<String, Object> download(TrackedDownload t, Episode e, EpisodeFile file, Instant now) {
        ClientItem item = t.item;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", 0);
        if (t.series != null) {
            r.put("seriesId", t.series.id());
        }
        if (e != null) {
            r.put("episodeId", e.id());
            r.put("seasonNumber", e.seasonNumber());
        }
        r.put("languages", t.languages);
        r.put("quality", quality(t.quality));
        r.put("customFormats", t.customFormats.stream().map(c -> Map.of("id", c.id(), "name", c.name())).toList());
        r.put("customFormatScore", t.customFormatScore);
        r.put("size", item.totalSize());
        r.put("title", item.title());
        r.put("sizeleft", item.remainingSize());
        r.put("timeleft", item.remainingSeconds() == null ? null : timeSpan(Duration.ofSeconds(
                item.remainingSeconds())));
        r.put("estimatedCompletionTime", item.remainingSeconds() == null ? null
                : now.plusSeconds(item.remainingSeconds()));
        r.put("added", t.added);
        r.put("status", tracked.unavailable(item.clientId()) ? "downloadClientUnavailable" : item.status());
        r.put("trackedDownloadStatus", t.status);
        r.put("trackedDownloadState", t.state);
        r.put("statusMessages", t.messages.stream().map(m -> Map.of("title", m.title(), "messages", m.messages()))
                .toList());
        if ("failed".equals(item.status()) && item.message() != null) {
            r.put("errorMessage", item.message());
        }
        r.put("downloadId", item.downloadId());
        r.put("protocol", "torrent");
        r.put("downloadClient", item.clientName());
        r.put("downloadClientHasPostImportCategory", false);
        r.put("indexer", t.indexer);
        r.put("outputPath", item.outputPath());
        r.put("episodeHasFile", file != null);
        return r;
    }

    private static Object episode(Episode e, EpisodeFile file) {
        return SonarrResources.episode(e, null, file == null ? 0 : file.id());
    }

    /**
     * A quality as Sonarr's API writes it.
     *
     * @param q the quality, or {@code null}
     * @return {@code {quality: {...}, revision: {...}}}
     */
    public static Map<String, Object> quality(QualityModel q) {
        if (q == null) {
            return null;
        }
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("id", q.quality.id());
        quality.put("name", q.quality.name());
        quality.put("source", q.quality.source());
        quality.put("resolution", q.quality.resolution());
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("version", q.revision.version);
        revision.put("real", q.revision.real);
        revision.put("isRepack", q.revision.isRepack);
        return Map.of("quality", quality, "revision", revision);
    }

    /**
     * A duration as .NET writes a {@code TimeSpan}: {@code hh:mm:ss}, or {@code d.hh:mm:ss}.
     *
     * @param d the duration
     * @return the text
     */
    public static String timeSpan(Duration d) {
        long s = Math.max(0, d.getSeconds());
        long days = s / 86_400;
        String hms = String.format("%02d:%02d:%02d", s % 86_400 / 3600, s % 3600 / 60, s % 60);
        return days > 0 ? days + "." + hms : hms;
    }

    /**
     * Sonarr's {@code HashConverter.GetHashInt31}: a positive int from a text.
     *
     * @param text the text
     * @return the hash
     */
    static int hash(String text) {
        byte[] sha = sha1(text);
        int h = ((sha[0] & 0xff) << 24) | ((sha[1] & 0xff) << 16) | ((sha[2] & 0xff) << 8) | (sha[3] & 0xff);
        return h & 0x7fffffff;
    }

    private static byte[] sha1(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-1")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sortTitle(Item i) {
        Object series = i.resource().get("series");
        return series instanceof com.catacumbas69.catasonarr.dto.SeriesResource r ? r.sortTitle()
                : String.valueOf(i.resource().get("title"));
    }

    private static double progress(Item i) {
        long size = ((Number) i.resource().get("size")).longValue();
        long left = ((Number) i.resource().getOrDefault("sizeleft", 0L)).longValue();
        return size == 0 ? 0 : 1.0 - (double) left / size;
    }

    private static long secondsLeft(Item i) {
        Object t = i.resource().get("estimatedCompletionTime");
        return t instanceof Instant instant ? instant.getEpochSecond() : Long.MAX_VALUE;
    }
}
