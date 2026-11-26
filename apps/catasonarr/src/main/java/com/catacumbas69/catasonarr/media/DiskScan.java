package com.catacumbas69.catasonarr.media;

import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.MediaInfo;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Settings;
import com.catacumbas69.catasonarr.parser.ParserText;
import com.catacumbas69.catasonarr.service.CatasonarrEvents;
import com.catacumbas69.catasonarr.service.EpisodeFileService;
import com.catacumbas69.catasonarr.service.EpisodeService;
import com.catacumbas69.catasonarr.service.HistoryService;
import com.catacumbas69.catasonarr.service.SettingsService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sonarr's {@code RescanSeries} (orch.md decision 122): forgets the files that left the series' folder, reads again
 * the ones that changed (size, or MediaInfo from an older reader), adds the video files found there (matched by
 * their names, kept where they are) and, when Settings say so, deletes empty folders or creates the series' folder.
 * Nothing is done when the root folder is missing or empty, so an unmounted disk never empties the library.
 */
@Component
public class DiskScan {

    /**
     * What a rescan did.
     *
     * @param added files added
     * @param removed files forgotten (no longer on disk)
     * @param updated files read again
     * @param rejected files found but not added, with why
     * @param skipped why nothing was done, or {@code null}
     */
    public record Result(int added, int removed, int updated, Map<String, String> rejected, String skipped) {

        /**
         * One line for the command's message.
         *
         * @return the message
         */
        public String message() {
            if (skipped != null) {
                return skipped;
            }
            return "Added " + added + ", removed " + removed + ", updated " + updated + ", not added "
                    + rejected.size();
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(DiskScan.class);

    private final EpisodeFileService files;
    private final EpisodeService episodes;
    private final EpisodeImporter importer;
    private final MediaInfoReader reader;
    private final HistoryService history;
    private final SettingsService settings;
    private final RecycleBin recycleBin;
    private final CatasonarrEvents events;

    /**
     * Creates the scanner.
     *
     * @param files the episode files
     * @param episodes episodes (to unmonitor those whose file went away, when Settings say so)
     * @param importer adds the files found
     * @param reader ffprobe
     * @param history the history
     * @param settings media management settings
     * @param recycleBin the recycle bin (never scanned)
     * @param events events
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public DiskScan(EpisodeFileService files, EpisodeService episodes, EpisodeImporter importer,
                    MediaInfoReader reader, HistoryService history, SettingsService settings, RecycleBin recycleBin,
                    CatasonarrEvents events) {
        this.files = files;
        this.episodes = episodes;
        this.importer = importer;
        this.reader = reader;
        this.history = history;
        this.settings = settings;
        this.recycleBin = recycleBin;
        this.events = events;
    }

    /**
     * Rescans a series' folder.
     *
     * @param series the series
     * @param actor who asked
     * @return what was done
     */
    public Result rescan(Series series, String actor) {
        Settings s = settings.get();
        Path folder = Path.of(series.path());
        Path root = folder.getParent();
        if (root == null || !Files.isDirectory(root) || empty(root)) {
            String why = "Root folder " + root + " is missing or empty: not scanning " + series.title();
            LOG.warn(why);
            return new Result(0, 0, 0, Map.of(), why);
        }
        if (!Files.isDirectory(folder) && s.createEmptySeriesFolders()) {
            try {
                Files.createDirectories(folder);
            } catch (IOException e) {
                LOG.warn("Unable to create {}: {}", folder, e.getMessage());
            }
        }
        List<Path> onDisk = Files.isDirectory(folder) ? EpisodeImporter.videoFiles(folder).stream()
                .filter(p -> !recycleBin.contains(p)).toList() : List.of();

        Map<String, EpisodeFile> known = new HashMap<>();
        int removed = 0;
        int updated = 0;
        for (EpisodeFile f : files.bySeries(series.id())) {
            Path p = folder.resolve(f.relativePath());
            if (!Files.isRegularFile(p)) {
                forget(series, f, actor, s);
                removed++;
                continue;
            }
            known.put(p.normalize().toString(), f);
            if (refresh(f, p)) {
                updated++;
            }
        }
        List<Path> found = new ArrayList<>();
        for (Path p : onDisk) {
            if (!known.containsKey(p.normalize().toString())) {
                found.add(p);
            }
        }
        Map<String, String> rejected = new LinkedHashMap<>();
        int added = 0;
        if (!found.isEmpty()) {
            for (EpisodeImporter.Outcome o : importer.importInPlace(series, found, actor)) {
                if (o.imported()) {
                    added++;
                } else {
                    rejected.put(folder.relativize(o.path()).toString(), o.rejection());
                }
            }
        }
        if (s.deleteEmptyFolders() && Files.isDirectory(folder)) {
            deleteEmptyFolders(folder);
        }
        Result result = new Result(added, removed, updated, rejected, null);
        if (added + removed + updated > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("seriesId", series.id());
            details.put("added", added);
            details.put("removed", removed);
            details.put("updated", updated);
            details.put("rejected", rejected.size());
            events.action("series", "rescanned", series.title(), actor, details);
        }
        LOG.info("Rescanned {}: {}", series.title(), result.message());
        return result;
    }

    /** Sonarr's {@code MediaFileTableCleanupService}: the file left the disk. */
    private void forget(Series series, EpisodeFile f, String actor, Settings s) {
        files.delete(f.id());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reason", "MissingFromDisk");
        history.record(HistoryService.EPISODE_FILE_DELETED, series.id(), f.episodeIds(),
                f.sceneName() != null ? f.sceneName() : ParserText.fileName(f.relativePath()), f.quality(),
                f.languages(), null, data);
        if (s.autoUnmonitorPreviouslyDownloadedEpisodes()) {
            episodes.setMonitored(f.episodeIds(), false, actor);
        }
        events.action("episodefile", "deleted", f.relativePath(), actor,
                Map.of("seriesId", series.id(), "episodeIds", f.episodeIds(), "reason", "MissingFromDisk"));
        LOG.info("{} is no longer on disk", f.relativePath());
    }

    /** Reads a file again when its size changed or its MediaInfo is from an older reader. */
    private boolean refresh(EpisodeFile f, Path p) {
        long size;
        try {
            size = Files.size(p);
        } catch (IOException e) {
            return false;
        }
        boolean stale = f.mediaInfo() == null || f.mediaInfo().schemaRevision() < MediaInfoReader.SCHEMA_REVISION;
        if (size == f.size() && !stale) {
            return false;
        }
        MediaInfo mi = reader.read(p);
        files.save(new EpisodeFile(f.id(), f.seriesId(), f.seasonNumber(), f.episodeIds(), f.relativePath(), size,
                f.dateAdded(), f.sceneName(), f.releaseGroup(), f.quality(), f.languages(),
                mi == null ? f.mediaInfo() : mi, f.originalFilePath(), f.indexerFlags(), f.releaseType()));
        return true;
    }

    private static boolean empty(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }

    private static void deleteEmptyFolders(Path folder) {
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failed) throws IOException {
                    if (!dir.equals(folder) && empty(dir)) {
                        Files.delete(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return dir.getFileName() != null && dir.getFileName().toString().startsWith(".") && !dir.equals(
                            folder) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Unable to delete empty folders in {}: {}", folder, e.getMessage());
        }
    }
}
