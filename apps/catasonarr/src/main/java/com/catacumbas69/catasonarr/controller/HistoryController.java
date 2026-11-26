package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.decision.Library;
import com.catacumbas69.catasonarr.download.TrackedDownloadService;
import com.catacumbas69.catasonarr.dto.PagingResource;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.History;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.HistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/history} (orch.md decision 122): grabs, imports, failures and deleted files, newest
 * first; Bazarr reads it to find new episode files.
 */
@Tag(name = "History (Sonarr API v3)")
@RestController
public class HistoryController {

    private final HistoryService history;
    private final ActivityResources resources;
    private final TrackedDownloadService downloads;
    private final Library library;

    /**
     * Creates the controller.
     *
     * @param history the history
     * @param resources the resources
     * @param downloads marks a grab as failed
     * @param library a season's episodes
     */
    public HistoryController(HistoryService history, ActivityResources resources, TrackedDownloadService downloads,
                             Library library) {
        this.history = history;
        this.resources = resources;
        this.downloads = downloads;
        this.library = library;
    }

    /**
     * A page of the history, newest first.
     *
     * @param page from 1
     * @param pageSize per page
     * @param sortKey only {@code date}
     * @param sortDirection {@code descending}
     * @param eventType Sonarr's number (1 grabbed, 3 imported, 4 failed, 5 deleted…) or name
     * @param episodeId only this episode
     * @param downloadId only this download
     * @param seriesIds only these series
     * @param includeSeries add each record's series
     * @param includeEpisode add each record's episode
     * @return the page
     */
    @Operation(summary = "A page of the history")
    @GetMapping("/api/v3/history")
    @SuppressWarnings("checkstyle:parameternumber")
    public PagingResource<Map<String, Object>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "date") String sortKey,
            @RequestParam(defaultValue = "descending") String sortDirection,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Integer episodeId,
            @RequestParam(required = false) String downloadId,
            @RequestParam(required = false) List<Integer> seriesIds,
            @RequestParam(defaultValue = "false") boolean includeSeries,
            @RequestParam(defaultValue = "false") boolean includeEpisode) {
        String type = ActivityResources.eventType(eventType);
        List<Integer> series = seriesIds == null || seriesIds.isEmpty() ? null : seriesIds;
        List<History> records = history.page(page, Math.max(1, pageSize), type, episodeId, downloadId, series);
        long total = history.count(type, episodeId, downloadId, series);
        return new PagingResource<>(page, pageSize, sortKey, sortDirection, (int) total,
                resources.history(records, includeSeries, includeEpisode));
    }

    /**
     * Everything since a time, oldest first.
     *
     * @param date the time
     * @param eventType only this event
     * @param includeSeries add each record's series
     * @param includeEpisode add each record's episode
     * @return the records
     */
    @Operation(summary = "The history since a time")
    @GetMapping("/api/v3/history/since")
    public List<Map<String, Object>> since(@RequestParam Instant date,
                                           @RequestParam(required = false) String eventType,
                                           @RequestParam(defaultValue = "false") boolean includeSeries,
                                           @RequestParam(defaultValue = "false") boolean includeEpisode) {
        return resources.history(history.since(date, ActivityResources.eventType(eventType)), includeSeries,
                includeEpisode);
    }

    /**
     * A series' history, newest first.
     *
     * @param seriesId the series
     * @param seasonNumber only this season
     * @param eventType only this event
     * @param includeSeries add each record's series
     * @param includeEpisode add each record's episode
     * @return the records
     */
    @Operation(summary = "A series' history")
    @GetMapping("/api/v3/history/series")
    public List<Map<String, Object>> series(@RequestParam int seriesId,
                                            @RequestParam(required = false) Integer seasonNumber,
                                            @RequestParam(required = false) String eventType,
                                            @RequestParam(defaultValue = "false") boolean includeSeries,
                                            @RequestParam(defaultValue = "false") boolean includeEpisode) {
        String type = ActivityResources.eventType(eventType);
        List<Integer> season = seasonNumber == null ? null : library.season(seriesId, seasonNumber).stream()
                .map(Episode::id).toList();
        List<History> records = history.bySeries(seriesId, seasonNumber, season).stream()
                .filter(h -> type == null || type.equals(h.eventType())).toList();
        return resources.history(records, includeSeries, includeEpisode);
    }

    /**
     * Marks a grab as failed: blocklisted and searched again, as Sonarr's {@code POST /history/failed/{id}}.
     *
     * @param id the grab's record
     * @param principal who
     * @param request the request
     * @return nothing
     */
    @Operation(summary = "Mark a grab as failed")
    @PostMapping("/api/v3/history/failed/{id}")
    public Map<String, Object> failed(@PathVariable int id, Principal principal, HttpServletRequest request) {
        History h = history.get(id);
        if (!HistoryService.GRABBED.equals(h.eventType()) || h.downloadId() == null) {
            throw new BadRequestException("id", "Only a grab can be marked as failed");
        }
        downloads.markFailed(h.downloadId(), RequestActor.of(principal, request));
        return Map.of();
    }
}
