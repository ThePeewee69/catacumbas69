package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.decision.CustomFormatCalculator;
import com.catacumbas69.catasonarr.decision.Library;
import com.catacumbas69.catasonarr.decision.ProfileQualities;
import com.catacumbas69.catasonarr.decision.Upgradable;
import com.catacumbas69.catasonarr.download.QueueService;
import com.catacumbas69.catasonarr.model.Blocklist;
import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.History;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.parser.ParserText;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Sonarr's history, blocklist and episode file resources (orch.md decision 122), with their custom formats and
 * whether the quality cutoff is met.
 */
@Component
public class ActivityResources {

    /** Sonarr's history event types, by the number its API takes in {@code eventType}. */
    public static final List<String> EVENT_TYPES = List.of("unknown", "grabbed", "seriesFolderImported",
            "downloadFolderImported", "downloadFailed", "episodeFileDeleted", "episodeFileRenamed", "downloadIgnored");

    private final Library library;
    private final CustomFormatCalculator formats;

    /**
     * Creates the resources.
     *
     * @param library the series and their profiles
     * @param formats custom formats
     */
    public ActivityResources(Library library, CustomFormatCalculator formats) {
        this.library = library;
        this.formats = formats;
    }

    /**
     * The event type Sonarr's API means by a number or a name.
     *
     * @param value e.g. {@code 1} or {@code grabbed}, or {@code null}
     * @return the name, or {@code null}
     */
    public static String eventType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Integer n = ParserText.tryParseInt(value);
        if (n != null) {
            return n > 0 && n < EVENT_TYPES.size() ? EVENT_TYPES.get(n) : null;
        }
        return EVENT_TYPES.stream().filter(t -> t.equalsIgnoreCase(value)).findFirst().orElse(null);
    }

    /**
     * History records.
     *
     * @param records the records
     * @param includeSeries add each record's series
     * @param includeEpisode add each record's episode
     * @return the resources
     */
    public List<Map<String, Object>> history(List<History> records, boolean includeSeries, boolean includeEpisode) {
        Map<Integer, Optional<Series>> series = new HashMap<>();
        Map<Integer, Episode> episodes = new HashMap<>();
        if (includeEpisode) {
            library.episodes(records.stream().map(History::episodeId).distinct().toList())
                    .forEach(e -> episodes.put(e.id(), e));
        }
        return records.stream().map(h -> {
            Optional<Series> s = series.computeIfAbsent(h.seriesId(), library::series);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id());
            m.put("episodeId", h.episodeId());
            m.put("seriesId", h.seriesId());
            m.put("sourceTitle", h.sourceTitle());
            m.put("languages", h.languages());
            m.put("quality", QueueService.quality(h.quality()));
            formats(m, s, h.sourceTitle(), h);
            m.put("date", h.date());
            m.put("downloadId", h.downloadId());
            m.put("eventType", h.eventType());
            m.put("data", h.data() == null ? Map.of() : h.data());
            if (includeSeries) {
                s.ifPresent(x -> m.put("series", SonarrResources.series(x, null)));
            }
            if (includeEpisode && episodes.containsKey(h.episodeId())) {
                m.put("episode", SonarrResources.episode(episodes.get(h.episodeId()), null));
            }
            return m;
        }).toList();
    }

    /**
     * Blocklist entries.
     *
     * @param entries the entries
     * @param includeSeries add each entry's series
     * @return the resources
     */
    public List<Map<String, Object>> blocklist(List<Blocklist> entries, boolean includeSeries) {
        Map<Integer, Optional<Series>> series = new HashMap<>();
        return entries.stream().map(b -> {
            Optional<Series> s = series.computeIfAbsent(b.seriesId(), library::series);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("seriesId", b.seriesId());
            m.put("episodeIds", b.episodeIds());
            m.put("sourceTitle", b.sourceTitle());
            m.put("languages", b.languages());
            m.put("quality", QueueService.quality(b.quality()));
            List<CustomFormat> cfs = s.map(x -> formats.forTitle(b.sourceTitle(), b.quality(), b.languages(),
                    b.size() == null ? 0 : b.size(), x)).orElse(List.of());
            m.put("customFormats", cfs.stream().map(c -> Map.of("id", c.id(), "name", c.name())).toList());
            m.put("date", b.date());
            m.put("protocol", b.protocol());
            m.put("indexer", b.indexer());
            m.put("message", b.message());
            if (includeSeries) {
                s.ifPresent(x -> m.put("series", SonarrResources.series(x, null)));
            }
            return m;
        }).toList();
    }

    /**
     * An episode file.
     *
     * @param f the file
     * @param series its series
     * @return the resource
     */
    public Map<String, Object> episodeFile(EpisodeFile f, Series series) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("seriesId", f.seriesId());
        m.put("seasonNumber", f.seasonNumber());
        m.put("relativePath", f.relativePath());
        m.put("path", series == null ? null : series.path() + "/" + f.relativePath());
        m.put("size", f.size());
        m.put("dateAdded", f.dateAdded());
        m.put("sceneName", f.sceneName());
        m.put("releaseGroup", f.releaseGroup());
        m.put("languages", f.languages());
        m.put("quality", QueueService.quality(f.quality()));
        List<CustomFormat> cfs = series == null ? List.of() : formats.forFile(f, series);
        QualityProfile profile = series == null ? null : library.profile(series);
        m.put("customFormats", cfs.stream().map(c -> Map.of("id", c.id(), "name", c.name())).toList());
        m.put("customFormatScore", profile == null ? 0 : ProfileQualities.score(profile, cfs));
        m.put("indexerFlags", f.indexerFlags());
        m.put("releaseType", releaseType(f.releaseType()));
        m.put("mediaInfo", mediaInfo(f));
        m.put("qualityCutoffNotMet", profile != null && Upgradable.qualityCutoffNotMet(profile, f.quality(), null));
        m.put("episodeIds", f.episodeIds());
        return m;
    }

    private void formats(Map<String, Object> m, Optional<Series> s, String title, History h) {
        if (s.isEmpty() || h.quality() == null) {
            m.put("customFormats", List.of());
            m.put("customFormatScore", 0);
            m.put("qualityCutoffNotMet", false);
            return;
        }
        long size = h.data() == null ? 0 : Optional.ofNullable(ParserText.tryParseInt(h.data().get("size")))
                .orElse(0);
        List<CustomFormat> cfs = formats.forTitle(title, h.quality(), h.languages(), size, s.get());
        QualityProfile profile = library.profile(s.get());
        m.put("customFormats", cfs.stream().map(c -> Map.of("id", c.id(), "name", c.name())).toList());
        m.put("customFormatScore", profile == null ? 0 : ProfileQualities.score(profile, cfs));
        m.put("qualityCutoffNotMet", profile != null && Upgradable.qualityCutoffNotMet(profile, h.quality(), null));
    }

    private static String releaseType(int value) {
        return switch (value) {
            case 1 -> "singleEpisode";
            case 2 -> "multiEpisode";
            case 3 -> "seasonPack";
            default -> "unknown";
        };
    }

    private static Map<String, Object> mediaInfo(EpisodeFile f) {
        var mi = f.mediaInfo();
        if (mi == null) {
            return null;
        }
        String scene = f.sceneName() != null ? f.sceneName() : ParserText.fileName(f.relativePath());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("audioBitrate", mi.audioBitrate());
        m.put("audioChannels", com.catacumbas69.catasonarr.media.MediaInfoFormatter.audioChannels(mi).doubleValue());
        m.put("audioCodec", com.catacumbas69.catasonarr.media.MediaInfoFormatter.audioCodec(mi, scene));
        m.put("audioLanguages", mi.audioLanguages() == null ? "" : String.join("/", mi.audioLanguages()));
        m.put("audioStreamCount", mi.audioStreamCount());
        m.put("videoBitDepth", mi.videoBitDepth());
        m.put("videoBitrate", mi.videoBitrate());
        m.put("videoCodec", com.catacumbas69.catasonarr.media.MediaInfoFormatter.videoCodec(mi, scene));
        m.put("videoFps", mi.videoFps());
        m.put("videoDynamicRange", com.catacumbas69.catasonarr.media.MediaInfoFormatter.videoDynamicRange(mi));
        m.put("videoDynamicRangeType", com.catacumbas69.catasonarr.media.MediaInfoFormatter.videoDynamicRangeType(mi));
        m.put("resolution", mi.width() + "x" + mi.height());
        long seconds = (long) mi.runTimeSeconds();
        m.put("runTime", String.format("%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60));
        m.put("scanType", mi.scanType());
        m.put("subtitles", mi.subtitles() == null ? "" : String.join("/", mi.subtitles()));
        return m;
    }
}
