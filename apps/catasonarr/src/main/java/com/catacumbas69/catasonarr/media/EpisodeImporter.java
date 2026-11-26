package com.catacumbas69.catasonarr.media;

import com.catacumbas69.catasonarr.decision.CustomFormatCalculator;
import com.catacumbas69.catasonarr.decision.Library;
import com.catacumbas69.catasonarr.decision.ProfileQualities;
import com.catacumbas69.catasonarr.decision.ReleaseMatcher;
import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.History;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.Languages;
import com.catacumbas69.catasonarr.model.MediaInfo;
import com.catacumbas69.catasonarr.model.NamingConfig;
import com.catacumbas69.catasonarr.model.QualityDefinition;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Settings;
import com.catacumbas69.catasonarr.parser.IsoLanguages;
import com.catacumbas69.catasonarr.parser.LanguageParser;
import com.catacumbas69.catasonarr.parser.MediaFileExtensions;
import com.catacumbas69.catasonarr.parser.ParsedEpisodeInfo;
import com.catacumbas69.catasonarr.parser.Parser;
import com.catacumbas69.catasonarr.parser.ParserText;
import com.catacumbas69.catasonarr.quality.Qualities;
import com.catacumbas69.catasonarr.quality.Quality;
import com.catacumbas69.catasonarr.quality.QualityDetectionSource;
import com.catacumbas69.catasonarr.quality.QualityModel;
import com.catacumbas69.catasonarr.quality.QualitySource;
import com.catacumbas69.catasonarr.quality.Revision;
import com.catacumbas69.catasonarr.service.CatasonarrEvents;
import com.catacumbas69.catasonarr.service.EpisodeFileNames;
import com.catacumbas69.catasonarr.service.EpisodeFileService;
import com.catacumbas69.catasonarr.service.FolderNames;
import com.catacumbas69.catasonarr.service.HistoryService;
import com.catacumbas69.catasonarr.service.NamingService;
import com.catacumbas69.catasonarr.service.QualityService;
import com.catacumbas69.catasonarr.service.SettingsService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Imports episode files (orch.md decision 122), as Sonarr's {@code ImportDecisionMaker} and
 * {@code ImportApprovedEpisodes}: each video file is matched to the series' episodes (by its name, or the release's
 * when it is the only one), its quality, languages, group and MediaInfo are read, and Sonarr's import rules decide
 * (not a sample, enough free space, an upgrade of what is there, not imported before, an episode title when one is
 * needed, the same episodes as the file it replaces). Accepted files are moved into the series' folder under Sonarr's
 * name (the old file goes to the recycle bin) with their subtitles and extras, or, from a rescan, kept where they are.
 */
@Component
@SuppressWarnings("checkstyle:nestedifdepth")
public class EpisodeImporter {

    /** How a file gets into the library. */
    public enum Mode {
        /** Moved from the download into the series' folder, renamed. */
        MOVE,
        /** Already in the series' folder (a rescan): kept where and as it is. */
        IN_PLACE
    }

    /**
     * The download the files come from.
     *
     * @param downloadId the torrent's hash
     * @param title the torrent's name
     * @param clientType the client's implementation, e.g. {@code QBittorrent}
     * @param clientName the client's name
     */
    public record Download(String downloadId, String title, String clientType, String clientName) {
    }

    /**
     * What happened to one file.
     *
     * @param path the file
     * @param episodeIds its episodes, possibly none
     * @param importedPath where it went, or {@code null}
     * @param rejection why it was not imported, or {@code null}
     * @param sample whether it is a sample
     */
    public record Outcome(Path path, List<Integer> episodeIds, String importedPath, String rejection, boolean sample) {

        /**
         * Whether the file was imported.
         *
         * @return whether it was
         */
        public boolean imported() {
            return importedPath != null;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(EpisodeImporter.class);
    private static final Pattern EXCLUDED_FOLDERS = Pattern.compile(
            "(?:^|/)(?:extras|@eadir|\\.@__thumb|extrafanart|plex versions|\\.[^/]+)(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXCLUDED_FILES = Pattern.compile("^\\._|^Thumbs\\.db$|^\\.DS_store$|\\.partial~$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXCLUDED_EXTRAS = Pattern.compile(
            "-(Trailer|Other|Behind Scenes|Deleted Scenes|Featurettes|Interviews|Scenes|Shorts)\\.[^.]+$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE_TITLE_TOKEN = Pattern.compile("\\{Episode[- ._]?(Clean)?Title(:\\d+)?}",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> SUBTITLES = Set.of(".aqt", ".ass", ".idx", ".jss", ".psb", ".rt", ".smi", ".srt",
            ".ssa", ".sub", ".txt", ".utf", ".utf8", ".utf-8", ".vtt");

    private final Library library;
    private final ReleaseMatcher matcher;
    private final CustomFormatCalculator formats;
    private final MediaInfoReader reader;
    private final EpisodeFileService files;
    private final HistoryService history;
    private final NamingService naming;
    private final QualityService qualities;
    private final SettingsService settings;
    private final RecycleBin recycleBin;
    private final PlexNotifier plex;
    private final CatasonarrEvents events;
    private final Clock clock;

    /** A file being decided on (Sonarr's {@code LocalEpisode}). */
    private static final class Local {
        private Path path;
        private long size;
        private ParsedEpisodeInfo info;
        private List<Episode> episodes = List.of();
        private QualityModel quality;
        private List<Language> languages = List.of();
        private String releaseGroup;
        private String sceneName;
        private MediaInfo mediaInfo;
        private List<CustomFormat> customFormats = List.of();
        private int customFormatScore;
        private String rejection;
        private boolean sample;

        private List<Integer> episodeIds() {
            return episodes.stream().map(Episode::id).toList();
        }
    }

    /**
     * Creates the importer.
     *
     * @param library the series and episodes
     * @param matcher maps names to episodes
     * @param formats custom formats
     * @param reader ffprobe
     * @param files the episode files
     * @param history the history
     * @param naming the naming
     * @param qualities quality definitions (their titles name files)
     * @param settings media management settings
     * @param recycleBin where replaced files go
     * @param plex Plex
     * @param events events
     * @param clock the time
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public EpisodeImporter(Library library, ReleaseMatcher matcher, CustomFormatCalculator formats,
                           MediaInfoReader reader, EpisodeFileService files, HistoryService history,
                           NamingService naming, QualityService qualities, SettingsService settings,
                           RecycleBin recycleBin, PlexNotifier plex, CatasonarrEvents events, Clock clock) {
        this.library = library;
        this.matcher = matcher;
        this.formats = formats;
        this.reader = reader;
        this.files = files;
        this.history = history;
        this.naming = naming;
        this.qualities = qualities;
        this.settings = settings;
        this.recycleBin = recycleBin;
        this.plex = plex;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The video files in a folder (or the file itself), without Sonarr's excluded folders and files.
     *
     * @param path a folder or a file
     * @return the video files
     */
    public static List<Path> videoFiles(Path path) {
        if (Files.isRegularFile(path)) {
            return MediaFileExtensions.isVideo(ParserText.extension(path.toString())) ? List.of(path) : List.of();
        }
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !EXCLUDED_FOLDERS.matcher(path.relativize(p.getParent() == null ? p : p.getParent())
                            .toString().replace('\\', '/') + "/").find() || p.getParent().equals(path))
                    .filter(p -> !EXCLUDED_FILES.matcher(p.getFileName().toString()).find())
                    .filter(p -> !EXCLUDED_EXTRAS.matcher(p.getFileName().toString()).find())
                    .filter(p -> MediaFileExtensions.isVideo(ParserText.extension(p.getFileName().toString())))
                    .sorted().toList();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Unable to list {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    /**
     * Imports a finished download into a series: moves and renames what is accepted.
     *
     * @param series the series it was grabbed for
     * @param path the download's folder or file, as Catasonarr sees it
     * @param download the download
     * @param actor who imports ({@code catasonarr} for completed downloads)
     * @return what happened to each video file
     */
    public List<Outcome> importDownload(Series series, Path path, Download download, String actor) {
        List<Path> videos = videoFiles(path);
        ParsedEpisodeInfo downloadInfo = download == null || download.title() == null ? null
                : Parser.parseTitle(download.title());
        ParsedEpisodeInfo folderInfo = Files.isDirectory(path) ? Parser.parseTitle(path.getFileName().toString())
                : null;
        List<Local> decided = decide(series, videos, downloadInfo, folderInfo, download, Mode.MOVE);
        return importApproved(series, decided, path, download, Mode.MOVE, actor);
    }

    /**
     * Adds files already in a series' folder (a rescan), where and as they are.
     *
     * @param series the series
     * @param found the files not yet known
     * @param actor who rescans
     * @return what happened to each file
     */
    public List<Outcome> importInPlace(Series series, List<Path> found, String actor) {
        List<Local> decided = decide(series, found, null, null, null, Mode.IN_PLACE);
        return importApproved(series, decided, Path.of(series.path()), null, Mode.IN_PLACE, actor);
    }

    private List<Local> decide(Series series, List<Path> videos, ParsedEpisodeInfo downloadInfo,
                               ParsedEpisodeInfo folderInfo, Download download, Mode mode) {
        QualityProfile profile = library.profile(series);
        Settings s = settings.get();
        NamingConfig names = naming.get();
        Map<Integer, EpisodeFile> existing = files.byEpisode(List.of(series.id()));
        List<History> downloadHistory = download == null || download.downloadId() == null ? List.of()
                : history.byDownloadId(download.downloadId());
        Map<String, String> grabData = downloadHistory.stream()
                .filter(h -> HistoryService.GRABBED.equals(h.eventType())).map(History::data)
                .filter(Objects::nonNull).findFirst().orElse(Map.of());
        List<Local> all = new ArrayList<>();
        for (Path video : videos) {
            Local l = new Local();
            l.path = video;
            try {
                l.size = Files.size(video);
            } catch (IOException e) {
                l.rejection = "Unable to read the file: " + e.getMessage();
                all.add(l);
                continue;
            }
            ParsedEpisodeInfo fileInfo = Parser.parsePath(video.toString());
            l.info = fileInfo;
            l.episodes = fileInfo == null || fileInfo.fullSeason ? List.of() : matcher.episodesFor(fileInfo, series);
            ParsedEpisodeInfo fallback = downloadInfo != null && !downloadInfo.fullSeason ? downloadInfo
                    : folderInfo != null && !folderInfo.fullSeason ? folderInfo : null;
            if (l.episodes.isEmpty() && videos.size() == 1 && fallback != null) {
                l.info = fallback;
                l.episodes = matcher.episodesFor(fallback, series);
            }
            if (l.info == null) {
                l.rejection = "Unable to parse file";
                all.add(l);
                continue;
            }
            if (l.info.fullSeason) {
                l.rejection = "Single episode file contains all episodes in seasons. Review file name or manually"
                        + " import";
                all.add(l);
                continue;
            }
            if (l.episodes.isEmpty()) {
                l.rejection = "Invalid season or episode";
                all.add(l);
                continue;
            }
            l.mediaInfo = reader.read(video);
            l.quality = quality(fileInfo, downloadInfo != null ? downloadInfo : folderInfo, l.mediaInfo, video);
            l.languages = languages(l.info, downloadInfo, series);
            l.releaseGroup = !ParserText.isBlank(l.info.releaseGroup) ? l.info.releaseGroup
                    : downloadInfo == null ? null : downloadInfo.releaseGroup;
            l.sceneName = sceneName(l, videos.size(), downloadInfo, download);
            String title = l.sceneName != null ? l.sceneName : ParserText.nameWithoutExtension(video.getFileName()
                    .toString());
            l.customFormats = formats.formats(new CustomFormatCalculator.Input(title, video.getFileName().toString(),
                    l.releaseGroup, l.quality, l.languages, l.size, integer(grabData.get("indexerFlags")),
                    l.info.releaseType().value(), series));
            l.customFormatScore = ProfileQualities.score(profile, l.customFormats);
            l.rejection = mode == Mode.MOVE ? rejection(l, series, profile, s, names, existing, downloadHistory)
                    : inPlaceRejection(l, existing);
            all.add(l);
        }
        return all;
    }

    private String rejection(Local l, Series series, QualityProfile profile, Settings s, NamingConfig names,
                             Map<Integer, EpisodeFile> existing, List<History> downloadHistory) {
        if (sample(l, series)) {
            l.sample = true;
            return "Sample";
        }
        if (!s.skipFreeSpaceCheckWhenImporting()) {
            long free = freeSpace(Path.of(series.path()));
            if (free >= 0 && free - l.size < (long) s.minimumFreeSpaceWhenImporting() * 1024 * 1024) {
                return "Not enough free space";
            }
        }
        for (Episode e : l.episodes) {
            EpisodeFile file = existing.get(e.id());
            if (file == null) {
                continue;
            }
            int compare = ProfileQualities.compare(profile, l.quality.quality.id(), file.quality().quality.id());
            if (compare < 0) {
                return "Not an upgrade for existing episode file(s). Existing quality: " + file.quality().quality.name()
                        + ". New Quality " + l.quality.quality.name() + ".";
            }
            if (compare == 0 && !"doNotPrefer".equals(s.downloadPropersAndRepacks())
                    && l.quality.revision.compareTo(file.quality().revision) < 0) {
                return "Not a quality revision upgrade for existing episode file(s)";
            }
            int currentScore = ProfileQualities.score(profile, formats.forFile(file, series));
            if (compare == 0 && l.customFormatScore < currentScore) {
                return "Not a Custom Format upgrade for existing episode file(s). New: ("
                        + l.customFormatScore + ") do not improve on Existing: (" + currentScore + ")";
            }
            if (!new HashSet<>(l.episodeIds()).containsAll(file.episodeIds())) {
                return "Episode file on disk contains more episodes than this file contains";
            }
        }
        String already = alreadyImported(l, downloadHistory);
        if (already != null) {
            return already;
        }
        return episodeTitle(l, series, s, names);
    }

    private static String inPlaceRejection(Local l, Map<Integer, EpisodeFile> existing) {
        for (Episode e : l.episodes) {
            if (existing.containsKey(e.id())) {
                return "Episode already has a file: " + existing.get(e.id()).relativePath();
            }
        }
        return null;
    }

    /** Sonarr's {@code AlreadyImportedSpecification}: imported from this download after it was grabbed. */
    private static String alreadyImported(Local l, List<History> downloadHistory) {
        Instant grabbed = downloadHistory.stream().filter(h -> HistoryService.GRABBED.equals(h.eventType()))
                .map(History::date).max(Comparator.naturalOrder()).orElse(null);
        for (History h : downloadHistory) {
            if (HistoryService.DOWNLOAD_FOLDER_IMPORTED.equals(h.eventType()) && l.episodeIds().contains(h.episodeId())
                    && (grabbed == null || h.date().isAfter(grabbed))) {
                return "Episode file already imported at " + h.date();
            }
        }
        return null;
    }

    /** Sonarr's {@code EpisodeTitleSpecification}. */
    private String episodeTitle(Local l, Series series, Settings s, NamingConfig names) {
        if ("never".equals(s.episodeTitleRequired()) || !names.renameEpisodes()) {
            return null;
        }
        String format = "daily".equals(series.seriesType()) ? names.dailyEpisodeFormat()
                : "anime".equals(series.seriesType()) ? names.animeEpisodeFormat() : names.standardEpisodeFormat();
        if (format == null || !EPISODE_TITLE_TOKEN.matcher(format).find()) {
            return null;
        }
        Episode first = l.episodes.get(0);
        if ("bulkSeasonReleases".equals(s.episodeTitleRequired())) {
            long sameDay = library.season(series.id(), first.seasonNumber()).stream()
                    .filter(e -> e.airDateUtc() != null && e.airDateUtc().equals(first.airDateUtc())).count();
            if (sameDay < 4) {
                return null;
            }
        }
        if (first.airDateUtc() != null && first.airDateUtc().isBefore(clock.instant().minus(48, ChronoUnit.HOURS))) {
            return null;
        }
        boolean missing = l.episodes.stream().anyMatch(e -> ParserText.isBlank(e.title()) || "TBA".equals(e.title()));
        return missing ? "Episode does not have a title and recently aired" : null;
    }

    /** Sonarr's {@code DetectSample}. */
    private static boolean sample(Local l, Series series) {
        if (l.episodes.stream().allMatch(e -> e.seasonNumber() == 0)) {
            return false;
        }
        String ext = ParserText.extension(l.path.toString()).toLowerCase(Locale.ROOT);
        if (ext.equals(".flv") || ext.equals(".strm") || l.mediaInfo == null) {
            return false;
        }
        int runtime = 0;
        for (Episode e : l.episodes) {
            runtime += e.runtime() > 0 ? e.runtime() : series.runtime();
        }
        if (runtime == 0) {
            runtime = 45;
        }
        int minimum = runtime <= 3 ? 15 : runtime <= 10 ? 90 : runtime <= 30 ? 300 : 600;
        return l.mediaInfo.runTimeSeconds() <= 0 || l.mediaInfo.runTimeSeconds() < minimum;
    }

    /** Sonarr's {@code AggregateQuality}: the name's, the release's, then the resolution ffprobe reads. */
    private static QualityModel quality(ParsedEpisodeInfo fileInfo, ParsedEpisodeInfo downloadInfo,
                                        MediaInfo mediaInfo, Path video) {
        QualityModel file = fileInfo == null || fileInfo.quality == null
                ? new QualityModel(MediaFileExtensions.qualityForExtension(ParserText.extension(video.toString())),
                null) : fileInfo.quality;
        QualityModel release = downloadInfo == null ? null : downloadInfo.quality;
        QualitySource source = QualitySource.of(file.quality);
        int resolution = file.quality.resolution();
        Revision revision = file.revision;
        if (release != null && release.quality.id() != Qualities.UNKNOWN.id()) {
            if (file.sourceDetectionSource != QualityDetectionSource.NAME
                    && release.sourceDetectionSource == QualityDetectionSource.NAME) {
                source = QualitySource.of(release.quality);
            }
            if (file.resolutionDetectionSource != QualityDetectionSource.NAME
                    && release.resolutionDetectionSource == QualityDetectionSource.NAME) {
                resolution = release.quality.resolution();
            }
            if (release.revision.compareTo(revision) > 0) {
                revision = release.revision;
            }
        }
        if (mediaInfo != null && (resolution == 0 || file.resolutionDetectionSource != QualityDetectionSource.NAME
                && (release == null || release.resolutionDetectionSource != QualityDetectionSource.NAME))) {
            int fromMediaInfo = resolution(mediaInfo);
            if (fromMediaInfo > 0) {
                resolution = fromMediaInfo;
            }
        }
        Quality quality = source == QualitySource.UNKNOWN && resolution == 0 ? file.quality
                : Qualities.findBySourceAndResolution(source, resolution);
        QualityModel model = new QualityModel(quality == null ? file.quality : quality, revision);
        model.sourceDetectionSource = file.sourceDetectionSource;
        model.resolutionDetectionSource = file.resolutionDetectionSource;
        model.revisionDetectionSource = file.revisionDetectionSource;
        return model;
    }

    /** Sonarr's resolution from MediaInfo. */
    private static int resolution(MediaInfo m) {
        int w = m.width();
        int h = m.height();
        if (w >= 3200 || h >= 2100) {
            return 2160;
        }
        if (w >= 1800 || h >= 1000) {
            return 1080;
        }
        if (w >= 1200 || h >= 700) {
            return 720;
        }
        if (w >= 1000 || h >= 560) {
            return 576;
        }
        return h > 0 ? 480 : 0;
    }

    /** Sonarr's {@code AggregateLanguage}: the file's, the release's, then the series' original language. */
    private static List<Language> languages(ParsedEpisodeInfo info, ParsedEpisodeInfo downloadInfo, Series series) {
        if (known(info.languages)) {
            return info.languages;
        }
        if (downloadInfo != null && known(downloadInfo.languages)) {
            return downloadInfo.languages;
        }
        return series.originalLanguage() == null ? List.of(Languages.UNKNOWN) : List.of(series.originalLanguage());
    }

    private static boolean known(List<Language> languages) {
        return languages != null && languages.stream().anyMatch(l -> l.id() != Languages.UNKNOWN.id());
    }

    /** Sonarr's {@code SceneNameCalculator}: the release's name when it is this file's, else a scene-like name. */
    private static String sceneName(Local l, int videos, ParsedEpisodeInfo downloadInfo, Download download) {
        if (download != null && download.title() != null && videos == 1 && downloadInfo != null
                && !downloadInfo.fullSeason) {
            return download.title();
        }
        String name = ParserText.nameWithoutExtension(l.path.getFileName().toString());
        ParsedEpisodeInfo parsed = Parser.parseTitle(name);
        if (parsed != null && parsed.quality != null
                && parsed.quality.sourceDetectionSource == QualityDetectionSource.NAME && !ParserText.isBlank(
                parsed.releaseGroup) && !name.contains(" ")) {
            return name;
        }
        return null;
    }

    private List<Outcome> importApproved(Series series, List<Local> decided, Path from, Download download, Mode mode,
                                         String actor) {
        List<Outcome> outcomes = new ArrayList<>();
        QualityProfile profile = library.profile(series);
        List<Local> approved = decided.stream().filter(l -> l.rejection == null)
                .sorted(Comparator.<Local>comparingInt(l -> l.episodes.size()).reversed()
                        .thenComparing((a, b) -> ProfileQualities.compare(profile, b.quality, a.quality))
                        .thenComparing(Comparator.<Local>comparingLong(l -> l.size).reversed()))
                .toList();
        Set<Integer> done = new HashSet<>();
        Settings s = settings.get();
        for (Local l : decided) {
            if (l.rejection != null) {
                outcomes.add(new Outcome(l.path, l.episodeIds(), null, l.rejection, l.sample));
            }
        }
        boolean any = false;
        for (Local l : approved) {
            if (l.episodeIds().stream().anyMatch(done::contains)) {
                outcomes.add(new Outcome(l.path, l.episodeIds(), null, "File is a duplicate of another file in this"
                        + " import", false));
                continue;
            }
            try {
                String imported = mode == Mode.MOVE ? move(series, l, from, download, s, actor)
                        : keep(series, l, actor);
                done.addAll(l.episodeIds());
                any = true;
                outcomes.add(new Outcome(l.path, l.episodeIds(), imported, null, false));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Unable to import {}: {}", l.path, e.getMessage());
                outcomes.add(new Outcome(l.path, l.episodeIds(), null, "Failed to import: " + e.getMessage(), false));
            }
        }
        if (any && mode == Mode.MOVE) {
            plex.seriesUpdated(series);
        }
        return outcomes;
    }

    private String keep(Series series, Local l, String actor) {
        Path root = Path.of(series.path());
        EpisodeFile file = new EpisodeFile(files.nextId(), series.id(), l.episodes.get(0).seasonNumber(),
                l.episodeIds(), root.relativize(l.path).toString().replace('\\', '/'), l.size, clock.instant(),
                l.sceneName, l.releaseGroup, l.quality, l.languages, l.mediaInfo, null, 0,
                l.info.releaseType().value());
        files.save(file);
        events.action("episodefile", "added", file.relativePath(), actor, details(series, file, null));
        LOG.info("Found {} for {} {}", file.relativePath(), series.title(), l.episodeIds());
        return l.path.toString();
    }

    private String move(Series series, Local l, Path from, Download download, Settings s, String actor)
            throws IOException {
        Path seriesPath = Path.of(series.path());
        NamingConfig names = naming.get();
        FolderNames.Names seriesNames = new FolderNames.Names(series.title(), series.year(), series.tvdbId(),
                series.tvMazeId(), series.tmdbId(), series.imdbId());
        int season = l.episodes.get(0).seasonNumber();
        String extension = ParserText.extension(l.path.getFileName().toString());
        String originalFilePath = (Files.isDirectory(from) ? from.getParent() : from.getParent() == null ? from
                : from.getParent()).relativize(l.path).toString().replace('\\', '/');
        int fileId = files.nextId();
        EpisodeFile draft = new EpisodeFile(fileId, series.id(), season, l.episodeIds(), l.path.getFileName()
                .toString(), l.size, clock.instant(), l.sceneName, l.releaseGroup, l.quality, l.languages, l.mediaInfo,
                originalFilePath, 0, l.info.releaseType().value());
        String name = EpisodeFileNames.fileName(names, new EpisodeFileNames.Input(seriesNames, series.seriesType(),
                l.episodes, draft, qualityTitle(l.quality), l.customFormats), extension);
        String relative = (series.seasonFolder() ? FolderNames.seasonFolder(names, seriesNames, season) + "/" : "")
                + name;
        Path target = seriesPath.resolve(relative).normalize();
        if (!target.startsWith(seriesPath)) {
            throw new IOException("The file name leaves the series' folder: " + relative);
        }

        List<EpisodeFile> replaced = files.byEpisode(List.of(series.id())).values().stream()
                .filter(f -> f.episodeIds().stream().anyMatch(l.episodeIds()::contains)).distinct().toList();
        for (EpisodeFile old : replaced) {
            Path oldPath = seriesPath.resolve(old.relativePath());
            recycleWithExtras(series, oldPath);
            files.delete(old.id());
            Map<String, String> data = new LinkedHashMap<>();
            data.put("reason", "Upgrade");
            history.record(HistoryService.EPISODE_FILE_DELETED, series.id(), old.episodeIds(),
                    old.sceneName() != null ? old.sceneName() : ParserText.fileName(old.relativePath()), old.quality(),
                    old.languages(), null, data);
        }
        Files.createDirectories(target.getParent());
        if (Files.exists(target) && !Files.isSameFile(target, l.path)) {
            recycleBin.recycle(target, binFolder(series, target));
        }
        Files.move(l.path, target, StandardCopyOption.REPLACE_EXISTING);

        EpisodeFile file = new EpisodeFile(fileId, series.id(), season, l.episodeIds(),
                seriesPath.relativize(target).toString().replace('\\', '/'), l.size, clock.instant(), l.sceneName,
                l.releaseGroup, l.quality, l.languages, l.mediaInfo, originalFilePath, grabFlags(download),
                l.info.releaseType().value());
        files.save(file);
        int extras = s.importExtraFiles() ? importExtras(l, target, Files.isDirectory(from), s) : 0;

        Map<String, String> data = new LinkedHashMap<>();
        data.put("fileId", String.valueOf(file.id()));
        data.put("droppedPath", l.path.toString());
        data.put("importedPath", target.toString());
        data.put("downloadClient", download == null ? null : download.clientType());
        data.put("downloadClientName", download == null ? null : download.clientName());
        data.put("releaseGroup", l.releaseGroup);
        data.put("customFormatScore", String.valueOf(l.customFormatScore));
        data.put("size", String.valueOf(l.size));
        data.put("indexerFlags", String.valueOf(file.indexerFlags()));
        data.put("releaseType", String.valueOf(file.releaseType()));
        history.record(HistoryService.DOWNLOAD_FOLDER_IMPORTED, series.id(), l.episodeIds(),
                l.sceneName != null ? l.sceneName : ParserText.nameWithoutExtension(l.path.getFileName().toString()),
                l.quality, l.languages, download == null ? null : download.downloadId(), data);

        Map<String, Object> details = details(series, file, download);
        details.put("droppedPath", l.path.toString());
        details.put("extras", extras);
        details.put("replaced", replaced.stream().map(EpisodeFile::relativePath).toList());
        events.action("episodefile", "imported", file.relativePath(), actor, details);
        LOG.info("Imported {} as {} ({} extras, replaced {})", l.path, target, extras, replaced.size());
        return target.toString();
    }

    private int grabFlags(Download download) {
        if (download == null || download.downloadId() == null) {
            return 0;
        }
        return history.grabbed(download.downloadId()).stream().map(History::data).filter(Objects::nonNull)
                .map(d -> integer(d.get("indexerFlags"))).findFirst().orElse(0);
    }

    private String qualityTitle(QualityModel quality) {
        return qualities.definitions().stream().filter(d -> d.quality() == quality.quality.id())
                .map(QualityDefinition::title).findFirst().orElse(quality.quality.name());
    }

    /**
     * Deletes an episode file (Sonarr's {@code DELETE /api/v3/episodefile}): to the recycle bin with its extras, and
     * forgotten.
     *
     * @param series its series
     * @param file the file
     * @param actor who asked
     * @throws IOException when it cannot be moved
     */
    public void delete(Series series, EpisodeFile file, String actor) throws IOException {
        recycleWithExtras(series, Path.of(series.path()).resolve(file.relativePath()));
        files.delete(file.id());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reason", "Manual");
        history.record(HistoryService.EPISODE_FILE_DELETED, series.id(), file.episodeIds(),
                file.sceneName() != null ? file.sceneName() : ParserText.fileName(file.relativePath()),
                file.quality(), file.languages(), null, data);
        events.action("episodefile", "deleted", file.relativePath(), actor,
                Map.of("seriesId", series.id(), "episodeIds", file.episodeIds(), "reason", "Manual"));
    }

    /** The old file and the files next to it named after it (subtitles, .nfo), to the recycle bin. */
    private void recycleWithExtras(Series series, Path oldVideo) throws IOException {
        String base = ParserText.nameWithoutExtension(oldVideo.getFileName().toString());
        Path folder = oldVideo.getParent();
        if (folder != null && Files.isDirectory(folder)) {
            try (Stream<Path> siblings = Files.list(folder)) {
                for (Path p : siblings.filter(Files::isRegularFile).filter(p -> !p.equals(oldVideo))
                        .filter(p -> p.getFileName().toString().startsWith(base + ".")).toList()) {
                    recycleBin.recycle(p, binFolder(series, p));
                }
            }
        }
        recycleBin.recycle(oldVideo, binFolder(series, oldVideo));
    }

    /** The folder inside the bin: the file's folder from the root folder, e.g. {@code Andor (2022)/Season 01}. */
    private static String binFolder(Series series, Path file) {
        Path root = Path.of(series.path()).getParent();
        Path parent = file.getParent();
        if (root == null || parent == null || !parent.startsWith(root)) {
            return Path.of(series.path()).getFileName().toString();
        }
        return root.relativize(parent).toString();
    }

    /** Sonarr's {@code ImportExtraFiles}: subtitles with their language, other extras by the video's name. */
    private int importExtras(Local l, Path target, boolean recursive, Settings s) {
        Set<String> wanted = Arrays.stream(s.extraFileExtensions().split(",")).map(e -> e.strip())
                .map(e -> e.startsWith(".") ? e.substring(1) : e).filter(e -> !e.isEmpty())
                .map(e -> "." + e.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Path folder = l.path.getParent();
        if (wanted.isEmpty() || folder == null) {
            return 0;
        }
        List<Path> candidates;
        try (Stream<Path> walk = recursive ? Files.walk(folder) : Files.list(folder)) {
            candidates = walk.filter(Files::isRegularFile).filter(p -> wanted.contains(ParserText.extension(
                    p.getFileName().toString()).toLowerCase(Locale.ROOT))).sorted().toList();
        } catch (IOException e) {
            LOG.warn("Unable to list extras next to {}: {}", l.path, e.getMessage());
            return 0;
        }
        String sourceBase = ParserText.nameWithoutExtension(l.path.getFileName().toString());
        String targetBase = ParserText.nameWithoutExtension(target.getFileName().toString());
        List<Path> subtitles = candidates.stream().filter(p -> SUBTITLES.contains(ext(p))).toList();
        List<Path> others = candidates.stream().filter(p -> !SUBTITLES.contains(ext(p))).toList();
        int count = 0;

        List<Path> matchingSubs = new ArrayList<>(subtitles.stream().filter(p -> matches(p, sourceBase, l)).toList());
        if (matchingSubs.isEmpty() && !subtitles.isEmpty() && videoFiles(folder).size() == 1) {
            matchingSubs.addAll(subtitles);
        }
        Map<String, List<Path>> groups = new LinkedHashMap<>();
        for (Path p : matchingSubs) {
            String name = p.getFileName().toString();
            Language language = LanguageParser.parseSubtitleLanguage(name);
            List<String> tags = LanguageParser.parseLanguageTags(name);
            groups.computeIfAbsent(language.id() + "|" + String.join(".", tags) + "|" + ext(p),
                    k -> new ArrayList<>()).add(p);
        }
        for (List<Path> group : groups.values()) {
            int copy = 1;
            for (Path p : group) {
                String name = p.getFileName().toString();
                Language language = LanguageParser.parseSubtitleLanguage(name);
                List<String> tags = LanguageParser.parseLanguageTags(name);
                StringBuilder suffix = new StringBuilder();
                if (group.size() > 1) {
                    suffix.append('.').append(copy);
                }
                IsoLanguages.IsoLanguage iso = language.id() == Languages.UNKNOWN.id() ? null
                        : IsoLanguages.get(language);
                if (iso != null) {
                    suffix.append('.').append(iso.twoLetterCode());
                }
                if (!tags.isEmpty()) {
                    suffix.append('.').append(String.join(".", tags));
                }
                if (transfer(p, target.resolveSibling(targetBase + suffix + ext(p)))) {
                    count++;
                    copy++;
                }
            }
        }

        boolean hasNfo = false;
        for (Path p : others) {
            if (ext(p).equals(".nfo")) {
                if (hasNfo) {
                    continue;
                }
                hasNfo = true;
            }
            if (matches(p, sourceBase, l) && transfer(p, target.resolveSibling(targetBase + ext(p)))) {
                count++;
            }
        }
        return count;
    }

    private static boolean matches(Path p, String sourceBase, Local l) {
        String name = ParserText.nameWithoutExtension(p.getFileName().toString());
        if (name.toLowerCase(Locale.ROOT).startsWith(sourceBase.toLowerCase(Locale.ROOT))) {
            return true;
        }
        ParsedEpisodeInfo info = Parser.parsePath(p.toString());
        return info != null && info.episodeNumbers.length > 0 && l.info != null
                && info.seasonNumber == l.info.seasonNumber && Arrays.equals(info.episodeNumbers,
                l.info.episodeNumbers);
    }

    private static boolean transfer(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to import extra file {}: {}", from, e.getMessage());
            return false;
        }
    }

    private static String ext(Path p) {
        return ParserText.extension(p.getFileName().toString()).toLowerCase(Locale.ROOT);
    }

    private static long freeSpace(Path path) {
        Path p = path;
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        try {
            return p == null ? -1 : Files.getFileStore(p).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    private static Map<String, Object> details(Series series, EpisodeFile file, Download download) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("seriesId", series.id());
        details.put("episodeIds", file.episodeIds());
        details.put("quality", file.quality().toString());
        details.put("size", file.size());
        if (download != null) {
            details.put("downloadId", download.downloadId());
        }
        return details;
    }

    private static int integer(String s) {
        return ParserText.tryParseInt(s) == null ? 0 : ParserText.tryParseInt(s);
    }
}
