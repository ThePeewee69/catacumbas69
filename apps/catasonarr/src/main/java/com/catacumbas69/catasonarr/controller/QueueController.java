package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.download.QueueService;
import com.catacumbas69.catasonarr.dto.PagingResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/queue} (orch.md decisions 120 and 122): the downloads followed in the clients'
 * {@code catasonarr} category and the releases held back, one item per episode, as Sonarr pages, sorts, removes and
 * grabs them. Seerr asks for it every minute.
 */
@Tag(name = "Queue (Sonarr API v3)")
@RestController
public class QueueController {

    private final QueueService queue;

    /**
     * Creates the controller.
     *
     * @param queue the queue
     */
    public QueueController(QueueService queue) {
        this.queue = queue;
    }

    /**
     * The queue, a page at a time; Sonarr sorts it by the time left by default.
     *
     * @param page the page, from 1
     * @param pageSize records per page
     * @param sortKey what to sort by
     * @param sortDirection {@code ascending}, {@code descending} or {@code default}
     * @param includeUnknownSeriesItems add downloads not matched to a series
     * @param includeSeries add each item's series
     * @param includeEpisode add each item's episode
     * @param seriesIds only these series
     * @return the page
     */
    @Operation(summary = "The queue, a page at a time")
    @GetMapping("/api/v3/queue")
    @SuppressWarnings("checkstyle:parameternumber")
    public PagingResource<Map<String, Object>> queue(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "timeleft") String sortKey,
            @RequestParam(defaultValue = "ascending") String sortDirection,
            @RequestParam(defaultValue = "false") boolean includeUnknownSeriesItems,
            @RequestParam(defaultValue = "false") boolean includeSeries,
            @RequestParam(defaultValue = "false") boolean includeEpisode,
            @RequestParam(required = false) List<Integer> seriesIds) {
        List<QueueService.Item> all = queue.items(includeSeries, includeEpisode, includeUnknownSeriesItems);
        if (seriesIds != null && !seriesIds.isEmpty()) {
            all = all.stream().filter(i -> seriesIds.contains(i.resource().get("seriesId"))).toList();
        }
        all = QueueService.sort(all, sortKey, !"descending".equals(sortDirection));
        int size = Math.max(1, pageSize);
        int from = Math.min(all.size(), (Math.max(1, page) - 1) * size);
        List<Map<String, Object>> records = all.subList(from, Math.min(all.size(), from + size)).stream()
                .map(QueueService.Item::resource).toList();
        return new PagingResource<>(page, pageSize, sortKey, sortDirection, all.size(), records);
    }

    /**
     * The whole queue, without pages.
     *
     * @param seriesId only this series
     * @param episodeIds only these episodes
     * @param includeSeries add each item's series
     * @param includeEpisode add each item's episode
     * @return the items
     */
    @Operation(summary = "The whole queue")
    @GetMapping("/api/v3/queue/details")
    public List<Map<String, Object>> details(@RequestParam(required = false) Integer seriesId,
                                             @RequestParam(required = false) List<Integer> episodeIds,
                                             @RequestParam(defaultValue = "false") boolean includeSeries,
                                             @RequestParam(defaultValue = "false") boolean includeEpisode) {
        return queue.items(includeSeries, includeEpisode, false).stream().map(QueueService.Item::resource)
                .filter(r -> seriesId == null || seriesId.equals(r.get("seriesId")))
                .filter(r -> episodeIds == null || episodeIds.isEmpty() || episodeIds.contains(r.get("episodeId")))
                .toList();
    }

    /**
     * How many items there are, and whether any has an error or a warning.
     *
     * @return the counts
     */
    @Operation(summary = "The queue's counts")
    @GetMapping("/api/v3/queue/status")
    public Map<String, Object> status() {
        return queue.status();
    }

    /**
     * Removes an item: its whole download, or its held-back release.
     *
     * @param id the item
     * @param removeFromClient also remove the torrent and its data from the client
     * @param blocklist blocklist the release and search again
     * @param skipRedownload do not search again
     * @param principal who
     * @param request the request
     */
    @Operation(summary = "Remove a queue item")
    @DeleteMapping("/api/v3/queue/{id}")
    public void remove(@PathVariable int id, @RequestParam(defaultValue = "true") boolean removeFromClient,
                       @RequestParam(defaultValue = "false") boolean blocklist,
                       @RequestParam(defaultValue = "false") boolean skipRedownload, Principal principal,
                       HttpServletRequest request) {
        queue.remove(List.of(id), removeFromClient, blocklist, skipRedownload, RequestActor.of(principal, request));
    }

    /**
     * Removes items.
     *
     * @param body {@code {"ids": [...]}}
     * @param removeFromClient also remove the torrents and their data from the client
     * @param blocklist blocklist the releases and search again
     * @param skipRedownload do not search again
     * @param principal who
     * @param request the request
     */
    @Operation(summary = "Remove queue items")
    @DeleteMapping("/api/v3/queue/bulk")
    public void removeBulk(@RequestBody Map<String, List<Integer>> body,
                           @RequestParam(defaultValue = "true") boolean removeFromClient,
                           @RequestParam(defaultValue = "false") boolean blocklist,
                           @RequestParam(defaultValue = "false") boolean skipRedownload, Principal principal,
                           HttpServletRequest request) {
        queue.remove(body.getOrDefault("ids", List.of()), removeFromClient, blocklist, skipRedownload,
                RequestActor.of(principal, request));
    }

    /**
     * Grabs a held-back release now.
     *
     * @param id the item
     * @param principal who
     * @param request the request
     */
    @Operation(summary = "Grab a held-back release now")
    @PostMapping("/api/v3/queue/grab/{id}")
    public void grab(@PathVariable int id, Principal principal, HttpServletRequest request) {
        queue.grab(List.of(id), RequestActor.of(principal, request));
    }

    /**
     * Grabs held-back releases now.
     *
     * @param body {@code {"ids": [...]}}
     * @param principal who
     * @param request the request
     */
    @Operation(summary = "Grab held-back releases now")
    @PostMapping("/api/v3/queue/grab/bulk")
    public void grabBulk(@RequestBody Map<String, List<Integer>> body, Principal principal,
                         HttpServletRequest request) {
        queue.grab(body.getOrDefault("ids", List.of()), RequestActor.of(principal, request));
    }
}
