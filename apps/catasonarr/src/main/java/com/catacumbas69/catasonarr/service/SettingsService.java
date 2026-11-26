package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.Settings;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Catasonarr's settings of indexers, download clients, media management and Plex (orch.md decision 122), Sonarr's
 * defaults until they change.
 */
@Service
public class SettingsService {

    private final MongoTemplate mongo;
    private final CatasonarrEvents events;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param events events
     */
    public SettingsService(MongoTemplate mongo, CatasonarrEvents events) {
        this.mongo = mongo;
        this.events = events;
    }

    /**
     * The settings.
     *
     * @return the settings, or Sonarr's defaults
     */
    public Settings get() {
        Settings s = mongo.findById(Settings.ID, Settings.class);
        return s == null ? Settings.DEFAULT : s;
    }

    /**
     * Saves the settings; the RSS sync is at least 10 minutes apart (or off), as in Sonarr.
     *
     * @param settings the new settings
     * @param actor who changed them
     * @return the settings saved
     */
    public Settings save(Settings settings, String actor) {
        if (settings.rssSyncInterval() != 0 && settings.rssSyncInterval() < 10) {
            throw new BadRequestException("rssSyncInterval",
                    "RSS sync interval must be 0 (off) or at least 10 minutes");
        }
        if (settings.recycleBinCleanupDays() < 0 || settings.minimumAge() < 0 || settings.maximumSize() < 0) {
            throw new BadRequestException("settings", "Days, minutes and sizes cannot be negative");
        }
        if (settings.downloadPropersAndRepacks() != null && !java.util.List.of("preferAndUpgrade", "doNotUpgrade",
                "doNotPrefer").contains(settings.downloadPropersAndRepacks())) {
            throw new BadRequestException("downloadPropersAndRepacks",
                    "Must be preferAndUpgrade, doNotUpgrade or doNotPrefer");
        }
        if (settings.episodeTitleRequired() != null && !java.util.List.of("always", "bulkSeasonReleases", "never")
                .contains(settings.episodeTitleRequired())) {
            throw new BadRequestException("episodeTitleRequired", "Must be always, bulkSeasonReleases or never");
        }
        if (settings.plexEnabled() && (settings.plexUrl() == null || !settings.plexUrl().strip()
                .matches("https?://.+"))) {
            throw new BadRequestException("plexUrl", "Plex's address must start with http:// or https://");
        }
        if (settings.recycleBin() != null && !settings.recycleBin().isBlank()
                && !settings.recycleBin().strip().startsWith("/")) {
            throw new BadRequestException("recycleBin", "The recycle bin must be an absolute path");
        }
        Settings before = get();
        Settings saved = new Settings(Settings.ID, settings.rssSyncInterval(), settings.minimumAge(),
                settings.maximumSize(), settings.enableCompletedDownloadHandling(), settings.autoRedownloadFailed(),
                settings.autoRedownloadFailedFromInteractiveSearch(), orDefault(settings.downloadPropersAndRepacks(),
                Settings.DEFAULT.downloadPropersAndRepacks()), settings.autoUnmonitorPreviouslyDownloadedEpisodes(),
                settings.recycleBin() == null ? "" : settings.recycleBin().strip(), settings.recycleBinCleanupDays(),
                settings.createEmptySeriesFolders(), settings.deleteEmptyFolders(),
                settings.skipFreeSpaceCheckWhenImporting(), settings.minimumFreeSpaceWhenImporting(),
                settings.importExtraFiles(), orDefault(settings.extraFileExtensions(), ""),
                orDefault(settings.episodeTitleRequired(), "always"), settings.plexEnabled(),
                orDefault(settings.plexUrl(), ""), orDefault(settings.plexMapFrom(), ""),
                orDefault(settings.plexMapTo(), ""));
        mongo.save(saved);
        events.change("settings", "updated", "settings", actor, describe(before), describe(saved));
        return saved;
    }

    /**
     * The settings as events and the API show them.
     *
     * @param s the settings
     * @return their values by name
     */
    public static Map<String, Object> describe(Settings s) {
        return Map.ofEntries(Map.entry("rssSyncInterval", s.rssSyncInterval()), Map.entry("minimumAge", s.minimumAge()),
                Map.entry("maximumSize", s.maximumSize()),
                Map.entry("enableCompletedDownloadHandling", s.enableCompletedDownloadHandling()),
                Map.entry("autoRedownloadFailed", s.autoRedownloadFailed()),
                Map.entry("downloadPropersAndRepacks", s.downloadPropersAndRepacks()),
                Map.entry("recycleBin", s.recycleBin()), Map.entry("recycleBinCleanupDays", s.recycleBinCleanupDays()),
                Map.entry("importExtraFiles", s.importExtraFiles()),
                Map.entry("extraFileExtensions", s.extraFileExtensions()), Map.entry("plexEnabled", s.plexEnabled()),
                Map.entry("plexUrl", s.plexUrl()));
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
