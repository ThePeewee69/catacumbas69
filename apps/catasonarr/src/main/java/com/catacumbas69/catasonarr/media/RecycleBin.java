package com.catacumbas69.catasonarr.media;

import com.catacumbas69.catasonarr.model.Settings;
import com.catacumbas69.catasonarr.service.CatasonarrEvents;
import com.catacumbas69.catasonarr.service.SettingsService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sonarr's recycle bin (orch.md decision 122): a replaced or deleted episode file goes to {@code /data/.recycle}
 * (Settings), under its series' folder, and is deleted for good after 7 days. With no folder set, files are deleted
 * at once.
 */
@Component
public class RecycleBin {

    private static final Logger LOG = LoggerFactory.getLogger(RecycleBin.class);

    private final SettingsService settings;
    private final CatasonarrEvents events;
    private final Clock clock;

    /**
     * Creates the recycle bin.
     *
     * @param settings where it is and how long files stay
     * @param events events
     * @param clock the time
     */
    public RecycleBin(SettingsService settings, CatasonarrEvents events, Clock clock) {
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Whether a path is inside the recycle bin (so a rescan leaves it alone).
     *
     * @param path a path
     * @return whether it is in the bin
     */
    public boolean contains(Path path) {
        String bin = settings.get().recycleBin();
        return bin != null && !bin.isBlank() && path.toAbsolutePath().normalize().startsWith(Path.of(bin).normalize());
    }

    /**
     * Moves a file to the bin (or deletes it when there is no bin).
     *
     * @param file the file
     * @param subfolder the folder inside the bin, e.g. the series' folder name and its season folder
     * @throws IOException when it cannot be moved or deleted
     */
    public void recycle(Path file, String subfolder) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        String bin = settings.get().recycleBin();
        if (bin == null || bin.isBlank()) {
            Files.delete(file);
            LOG.info("Deleted {} (no recycle bin)", file);
            return;
        }
        Path folder = subfolder == null || subfolder.isBlank() ? Path.of(bin) : Path.of(bin).resolve(subfolder);
        Files.createDirectories(folder);
        Path target = free(folder.resolve(file.getFileName().toString()));
        Files.move(file, target);
        Files.setLastModifiedTime(target, FileTime.from(clock.instant()));
        LOG.info("Moved {} to the recycle bin: {}", file, target);
    }

    /**
     * Deletes what has been in the bin longer than the days set (Sonarr's {@code CleanupRecycleBin}), every day.
     */
    @Scheduled(initialDelayString = "PT15M", fixedDelayString = "PT24H")
    public void cleanup() {
        Settings s;
        try {
            s = settings.get();
        } catch (RuntimeException e) {
            LOG.warn("Recycle bin not cleaned, tried again tomorrow: {}", e.getMessage());
            return;
        }
        if (s.recycleBin() == null || s.recycleBin().isBlank() || s.recycleBinCleanupDays() <= 0) {
            return;
        }
        Path bin = Path.of(s.recycleBin());
        if (!Files.isDirectory(bin)) {
            return;
        }
        Instant limit = clock.instant().minus(s.recycleBinCleanupDays(), ChronoUnit.DAYS);
        AtomicInteger deleted = new AtomicInteger();
        try {
            Files.walkFileTree(bin, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.lastModifiedTime().toInstant().isBefore(limit)) {
                        Files.deleteIfExists(file);
                        deleted.incrementAndGet();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failed) throws IOException {
                    if (!dir.equals(bin)) {
                        try (var entries = Files.list(dir)) {
                            if (entries.findAny().isEmpty()) {
                                Files.delete(dir);
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Unable to clean the recycle bin {}: {}", bin, e.getMessage());
        }
        if (deleted.get() > 0) {
            LOG.info("Deleted {} files older than {} days from the recycle bin", deleted.get(),
                    s.recycleBinCleanupDays());
            events.action("recyclebin", "cleaned", s.recycleBin(), CatasonarrEvents.SELF,
                    Map.of("deleted", deleted.get(), "days", s.recycleBinCleanupDays()));
        }
    }

    private static Path free(Path target) {
        if (!Files.exists(target)) {
            return target;
        }
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path candidate = target.resolveSibling(base + "_" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}
