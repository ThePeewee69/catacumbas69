package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.media.PlexNotifier;
import com.catacumbas69.catasonarr.model.NamingConfig;
import com.catacumbas69.catasonarr.model.Settings;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.CatasonarrEvents;
import com.catacumbas69.catasonarr.service.NamingService;
import com.catacumbas69.catasonarr.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/config/naming}, {@code /mediamanagement}, {@code /indexer} and {@code /downloadclient}
 * (orch.md decision 122), each a view of Catasonarr's settings with Sonarr's fields and id 1; and Catasonarr's own
 * {@code /api/v3/catasonarr/settings} (everything, Plex included) and the Plex test. Plex's token is never here: it
 * is in a Secret.
 */
@Tag(name = "Settings (Sonarr API v3)")
@RestController
public class ConfigController {

    private static final Set<String> MEDIA_MANAGEMENT = Set.of("autoUnmonitorPreviouslyDownloadedEpisodes",
            "recycleBin", "recycleBinCleanupDays", "downloadPropersAndRepacks", "createEmptySeriesFolders",
            "deleteEmptyFolders", "episodeTitleRequired", "skipFreeSpaceCheckWhenImporting",
            "minimumFreeSpaceWhenImporting", "importExtraFiles", "extraFileExtensions");
    private static final Set<String> INDEXER = Set.of("minimumAge", "maximumSize", "rssSyncInterval");
    private static final Set<String> DOWNLOAD_CLIENT = Set.of("enableCompletedDownloadHandling",
            "autoRedownloadFailed", "autoRedownloadFailedFromInteractiveSearch");

    private final SettingsService settings;
    private final NamingService naming;
    private final PlexNotifier plex;
    private final CatasonarrEvents events;
    private final ObjectMapper json;

    /**
     * Creates the controller.
     *
     * @param settings the settings
     * @param naming the naming
     * @param plex Plex
     * @param events events
     * @param json Spring's mapper
     */
    public ConfigController(SettingsService settings, NamingService naming, PlexNotifier plex,
                            CatasonarrEvents events, ObjectMapper json) {
        this.settings = settings;
        this.naming = naming;
        this.plex = plex;
        this.events = events;
        this.json = json;
    }

    /**
     * Every setting, Catasonarr's way, with whether Plex's token is set.
     *
     * @return the settings
     */
    @Operation(summary = "Every setting (Catasonarr's own)")
    @GetMapping("/api/v3/catasonarr/settings")
    public Map<String, Object> all() {
        Map<String, Object> m = view(settings.get(), null);
        m.put("plexTokenSet", plex.hasToken());
        return m;
    }

    /**
     * Changes settings; what is not sent is kept.
     *
     * @param body the settings to change
     * @param principal who
     * @param request the request
     * @return {@code 202} with every setting
     */
    @Operation(summary = "Change settings (Catasonarr's own)")
    @PutMapping("/api/v3/catasonarr/settings")
    public ResponseEntity<Map<String, Object>> saveAll(@RequestBody Map<String, Object> body, Principal principal,
                                                       HttpServletRequest request) {
        save(body, null, RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(all());
    }

    /**
     * Checks that Plex answers at an address with the token of the Secret.
     *
     * @param body {@code plexUrl}
     * @return Plex's TV libraries
     */
    @Operation(summary = "Test Plex (Catasonarr's own)")
    @PostMapping("/api/v3/catasonarr/plex/test")
    public Map<String, Object> testPlex(@RequestBody Map<String, Object> body) {
        Object url = body.get("plexUrl");
        if (url == null || url.toString().isBlank()) {
            throw new BadRequestException("plexUrl", "Plex's address is empty");
        }
        return Map.of("libraries", plex.test(url.toString().strip()));
    }

    /**
     * Sonarr's media management settings.
     *
     * @return them
     */
    @Operation(summary = "Media management settings")
    @GetMapping("/api/v3/config/mediamanagement")
    public Map<String, Object> mediaManagement() {
        Map<String, Object> m = view(settings.get(), MEDIA_MANAGEMENT);
        m.put("rescanAfterRefresh", "always");
        m.put("fileDate", "none");
        m.put("setPermissionsLinux", false);
        m.put("chmodFolder", "755");
        m.put("chownGroup", "");
        m.put("copyUsingHardlinks", false);
        m.put("useScriptImport", false);
        m.put("scriptImportPath", "");
        m.put("enableMediaInfo", true);
        return m;
    }

    /**
     * Changes the media management settings.
     *
     * @param body Sonarr's resource
     * @param principal who
     * @param request the request
     * @return {@code 202} with them
     */
    @Operation(summary = "Change the media management settings")
    @PutMapping({"/api/v3/config/mediamanagement", "/api/v3/config/mediamanagement/{id}"})
    public ResponseEntity<Map<String, Object>> saveMediaManagement(@RequestBody Map<String, Object> body,
                                                                   Principal principal, HttpServletRequest request) {
        save(body, MEDIA_MANAGEMENT, RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mediaManagement());
    }

    /**
     * Sonarr's indexer settings.
     *
     * @return them
     */
    @Operation(summary = "Indexer settings")
    @GetMapping("/api/v3/config/indexer")
    public Map<String, Object> indexer() {
        Map<String, Object> m = view(settings.get(), INDEXER);
        m.put("retention", 0);
        return m;
    }

    /**
     * Changes the indexer settings.
     *
     * @param body Sonarr's resource
     * @param principal who
     * @param request the request
     * @return {@code 202} with them
     */
    @Operation(summary = "Change the indexer settings")
    @PutMapping({"/api/v3/config/indexer", "/api/v3/config/indexer/{id}"})
    public ResponseEntity<Map<String, Object>> saveIndexer(@RequestBody Map<String, Object> body,
                                                           Principal principal, HttpServletRequest request) {
        save(body, INDEXER, RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(indexer());
    }

    /**
     * Sonarr's download client settings.
     *
     * @return them
     */
    @Operation(summary = "Download client settings")
    @GetMapping("/api/v3/config/downloadclient")
    public Map<String, Object> downloadClient() {
        Map<String, Object> m = view(settings.get(), DOWNLOAD_CLIENT);
        m.put("downloadClientWorkingFolders", "_UNPACK_|_FAILED_");
        return m;
    }

    /**
     * Changes the download client settings.
     *
     * @param body Sonarr's resource
     * @param principal who
     * @param request the request
     * @return {@code 202} with them
     */
    @Operation(summary = "Change the download client settings")
    @PutMapping({"/api/v3/config/downloadclient", "/api/v3/config/downloadclient/{id}"})
    public ResponseEntity<Map<String, Object>> saveDownloadClient(@RequestBody Map<String, Object> body,
                                                                  Principal principal, HttpServletRequest request) {
        save(body, DOWNLOAD_CLIENT, RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(downloadClient());
    }

    /**
     * Sonarr's naming.
     *
     * @return it
     */
    @Operation(summary = "Naming")
    @GetMapping("/api/v3/config/naming")
    public Map<String, Object> naming() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = new LinkedHashMap<>(json.convertValue(naming.get(), Map.class));
        m.put("id", 1);
        return m;
    }

    /**
     * Changes the naming; what is not sent is kept. The episode formats must name the episode.
     *
     * @param body Sonarr's resource
     * @param principal who
     * @param request the request
     * @return {@code 202} with it
     */
    @Operation(summary = "Change the naming")
    @PutMapping({"/api/v3/config/naming", "/api/v3/config/naming/{id}"})
    public ResponseEntity<Map<String, Object>> saveNaming(@RequestBody Map<String, Object> body, Principal principal,
                                                          HttpServletRequest request) {
        NamingConfig before = naming.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = new LinkedHashMap<>(json.convertValue(before, Map.class));
        body.forEach((k, v) -> {
            if (merged.containsKey(k) && !"id".equals(k)) {
                merged.put(k, v);
            }
        });
        merged.put("id", NamingConfig.ID);
        NamingConfig after = json.convertValue(merged, NamingConfig.class);
        validate(after);
        naming.save(after);
        @SuppressWarnings("unchecked")
        Map<String, Object> beforeMap = json.convertValue(before, Map.class);
        events.change("naming", "updated", "naming", RequestActor.of(principal, request), beforeMap, merged);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(naming());
    }

    private static void validate(NamingConfig n) {
        if (n.multiEpisodeStyle() < 0 || n.multiEpisodeStyle() > 5) {
            throw new BadRequestException("multiEpisodeStyle", "Multi-episode style must be 0 to 5");
        }
        if (!n.renameEpisodes()) {
            return;
        }
        check("standardEpisodeFormat", n.standardEpisodeFormat(), true, false);
        check("dailyEpisodeFormat", n.dailyEpisodeFormat(), true, true);
        check("animeEpisodeFormat", n.animeEpisodeFormat(), true, false);
    }

    private static void check(String field, String format, boolean seasonEpisode, boolean airDate) {
        String f = format == null ? "" : format.toLowerCase(Locale.ROOT);
        boolean hasEpisode = f.contains("{episode") && f.contains("{season") || f.contains("{absolute");
        boolean hasDate = f.contains("{air-date") || f.contains("{air date") || f.contains("{air.date")
                || f.contains("{air_date");
        if (f.isBlank() || seasonEpisode && !hasEpisode && !(airDate && hasDate)) {
            throw new BadRequestException(field, "Must contain season and episode numbers"
                    + (airDate ? " or the air date" : "") + ", e.g. S{season:00}E{episode:00}");
        }
    }

    private Map<String, Object> view(Settings s, Set<String> only) {
        @SuppressWarnings("unchecked")
        Map<String, Object> all = json.convertValue(s, Map.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 1);
        all.forEach((k, v) -> {
            if (!"id".equals(k) && (only == null || only.contains(k))) {
                m.put(k, v);
            }
        });
        return m;
    }

    private void save(Map<String, Object> body, Set<String> only, String actor) {
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = new LinkedHashMap<>(json.convertValue(settings.get(), Map.class));
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (merged.containsKey(e.getKey()) && !"id".equals(e.getKey())
                    && (only == null || only.contains(e.getKey()))) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        merged.put("id", Settings.ID);
        try {
            settings.save(json.convertValue(merged, Settings.class), actor);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("settings", "Invalid value: " + e.getMessage());
        }
    }
}
