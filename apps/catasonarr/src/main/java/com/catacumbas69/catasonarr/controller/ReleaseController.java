package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.decision.DownloadDecision;
import com.catacumbas69.catasonarr.decision.DownloadDecisionMaker;
import com.catacumbas69.catasonarr.decision.Rejection;
import com.catacumbas69.catasonarr.decision.RemoteEpisode;
import com.catacumbas69.catasonarr.download.DownloadService;
import com.catacumbas69.catasonarr.download.QueueService;
import com.catacumbas69.catasonarr.indexers.ReleaseSearchService;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import com.catacumbas69.catasonarr.parser.ParsedEpisodeInfo;
import com.catacumbas69.catasonarr.quality.Qualities;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/release} (orch.md decision 122): the interactive search of an episode or a season, every
 * release with Sonarr's decision and why it was rejected, and grabbing one of them (even a rejected one, as in
 * Sonarr). The results are kept 30 minutes for the grab, as Sonarr's cache.
 */
@Tag(name = "Releases (Sonarr API v3)")
@RestController
public class ReleaseController {

    private static final Duration KEEP = Duration.ofMinutes(30);

    private record Cached(RemoteEpisode remote, Instant at) {
    }

    private final ReleaseSearchService search;
    private final DownloadDecisionMaker decisions;
    private final DownloadService downloads;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Creates the controller.
     *
     * @param search the searches
     * @param decisions the order Sonarr gives the releases
     * @param downloads grabs a release
     * @param clock the time
     */
    public ReleaseController(ReleaseSearchService search, DownloadDecisionMaker decisions, DownloadService downloads,
                             Clock clock) {
        this.search = search;
        this.decisions = decisions;
        this.downloads = downloads;
        this.clock = clock;
    }

    /**
     * Searches every indexer with interactive search on, for an episode or a season.
     *
     * @param episodeId the episode
     * @param seriesId the series, with {@code seasonNumber}
     * @param seasonNumber the season
     * @return the releases, best first
     */
    @Operation(summary = "Interactive search of an episode or a season")
    @GetMapping("/api/v3/release")
    public List<Map<String, Object>> search(@RequestParam(required = false) Integer episodeId,
                                            @RequestParam(required = false) Integer seriesId,
                                            @RequestParam(required = false) Integer seasonNumber) {
        List<DownloadDecision> found;
        if (episodeId != null) {
            found = search.episodeSearch(episodeId, true, true);
        } else if (seriesId != null && seasonNumber != null) {
            found = search.seasonSearch(seriesId, seasonNumber, false, true, true);
        } else {
            throw new BadRequestException("episodeId", "episodeId or seriesId and seasonNumber must be provided");
        }
        Instant now = clock.instant();
        cache.values().removeIf(c -> c.at().plus(KEEP).isBefore(now));
        List<Map<String, Object>> out = new ArrayList<>();
        int weight = found.size();
        for (DownloadDecision d : decisions.prioritize(found)) {
            RemoteEpisode r = d.remoteEpisode();
            cache.put(key(r.release.guid(), r.release.indexerId()), new Cached(r, now));
            out.add(resource(d, weight--, now));
        }
        return out;
    }

    /**
     * Grabs a release found by the last interactive search.
     *
     * @param body {@code guid} and {@code indexerId}
     * @param principal who
     * @param request the request
     * @return the release
     */
    @Operation(summary = "Grab a release from the interactive search")
    @PostMapping("/api/v3/release")
    public Map<String, Object> grab(@RequestBody Map<String, Object> body, Principal principal,
                                    HttpServletRequest request) {
        String guid = String.valueOf(body.get("guid"));
        int indexerId = body.get("indexerId") instanceof Number n ? n.intValue() : 0;
        Cached cached = cache.get(key(guid, indexerId));
        if (cached == null) {
            throw new NotFoundException("Couldn't find requested release in cache, cache timeout probably expired.");
        }
        RemoteEpisode remote = cached.remote();
        if (remote.series == null || remote.episodes.isEmpty()) {
            throw new BadRequestException("guid", "Unable to parse episodes in the release, will need to be"
                    + " manually provided");
        }
        remote.releaseSource = "InteractiveSearch";
        try {
            downloads.grab(remote, RequestActor.of(principal, request));
        } catch (DownloadService.NoClientException e) {
            throw new BadRequestException("downloadClientId", e.getMessage());
        }
        return Map.of("guid", guid, "indexerId", indexerId);
    }

    private static String key(String guid, int indexerId) {
        return indexerId + "|" + guid;
    }

    private static Map<String, Object> resource(DownloadDecision d, int weight, Instant now) {
        RemoteEpisode r = d.remoteEpisode();
        ReleaseInfo release = r.release;
        ParsedEpisodeInfo p = r.parsed;
        double minutes = release.ageMinutes(now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("guid", release.guid());
        m.put("quality", QueueService.quality(p == null ? null : p.quality));
        m.put("qualityWeight", p == null || p.quality == null ? 0 : Qualities.weight(p.quality.quality));
        m.put("age", (int) (minutes / 1440));
        m.put("ageHours", minutes / 60.0);
        m.put("ageMinutes", minutes);
        m.put("size", release.size());
        m.put("indexerId", release.indexerId());
        m.put("indexer", release.indexer());
        m.put("releaseGroup", p == null ? null : p.releaseGroup);
        m.put("releaseHash", p == null ? null : p.releaseHash);
        m.put("title", release.title());
        m.put("fullSeason", p != null && p.fullSeason);
        m.put("sceneSource", false);
        m.put("seasonNumber", p == null ? 0 : p.seasonNumber);
        m.put("languages", r.languages);
        m.put("airDate", p == null ? null : p.airDate);
        m.put("seriesTitle", p == null ? null : p.seriesTitle);
        m.put("episodeNumbers", p == null ? new int[0] : p.episodeNumbers);
        m.put("absoluteEpisodeNumbers", p == null ? new int[0] : p.absoluteEpisodeNumbers);
        m.put("mappedSeasonNumber", r.episodes.isEmpty() ? null : r.episodes.get(0).seasonNumber());
        m.put("mappedEpisodeNumbers", r.episodes.stream().map(Episode::episodeNumber).toList());
        m.put("mappedAbsoluteEpisodeNumbers", r.episodes.stream().map(Episode::absoluteEpisodeNumber)
                .filter(java.util.Objects::nonNull).toList());
        m.put("mappedSeriesId", r.series == null ? null : r.series.id());
        m.put("mappedEpisodeInfo", r.episodes.stream().map(e -> Map.of("id", e.id(), "seasonNumber",
                e.seasonNumber(), "episodeNumber", e.episodeNumber(), "title", e.title() == null ? "" : e.title()))
                .toList());
        m.put("approved", d.approved());
        m.put("temporarilyRejected", d.temporarilyRejected());
        m.put("rejected", !d.approved() && !d.temporarilyRejected());
        m.put("tvdbId", release.tvdbId());
        m.put("imdbId", release.imdbId());
        m.put("rejections", d.rejections().stream().map(Rejection::message).toList());
        m.put("publishDate", release.publishDate());
        m.put("commentUrl", release.infoUrl());
        m.put("downloadUrl", release.downloadUrl());
        m.put("infoUrl", release.infoUrl());
        m.put("episodeRequested", r.episodeRequested);
        m.put("downloadAllowed", r.series != null && !r.episodes.isEmpty());
        m.put("releaseWeight", weight);
        m.put("customFormats", r.customFormats.stream().map(c -> Map.of("id", c.id(), "name", c.name())).toList());
        m.put("customFormatScore", r.customFormatScore);
        m.put("magnetUrl", release.magnetUrl());
        m.put("infoHash", release.infoHash());
        m.put("seeders", release.seeders());
        m.put("leechers", release.peers() == null || release.seeders() == null ? null
                : release.peers() - release.seeders());
        m.put("protocol", "torrent");
        m.put("indexerFlags", release.indexerFlags());
        m.put("isDaily", p != null && p.isDaily());
        m.put("isAbsoluteNumbering", p != null && p.isAbsoluteNumbering());
        m.put("isPossibleSpecialEpisode", p != null && p.isPossibleSpecialEpisode());
        m.put("special", p != null && p.special);
        m.put("seriesId", r.series == null ? null : r.series.id());
        m.put("episodeIds", r.episodeIds());
        return m;
    }
}
