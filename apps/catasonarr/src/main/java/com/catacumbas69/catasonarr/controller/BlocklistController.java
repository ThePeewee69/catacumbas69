package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.PagingResource;
import com.catacumbas69.catasonarr.service.BlocklistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/blocklist} (orch.md decision 122): releases that failed or were marked as failed, never
 * grabbed again.
 */
@Tag(name = "Blocklist (Sonarr API v3)")
@RestController
public class BlocklistController {

    private final BlocklistService blocklist;
    private final ActivityResources resources;

    /**
     * Creates the controller.
     *
     * @param blocklist the blocklist
     * @param resources the resources
     */
    public BlocklistController(BlocklistService blocklist, ActivityResources resources) {
        this.blocklist = blocklist;
        this.resources = resources;
    }

    /**
     * A page of the blocklist, newest first.
     *
     * @param page from 1
     * @param pageSize per page
     * @param sortKey only {@code date}
     * @param sortDirection {@code descending}
     * @param seriesIds only these series
     * @param includeSeries add each entry's series
     * @return the page
     */
    @Operation(summary = "A page of the blocklist")
    @GetMapping("/api/v3/blocklist")
    public PagingResource<Map<String, Object>> page(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "10") int pageSize,
                                                    @RequestParam(defaultValue = "date") String sortKey,
                                                    @RequestParam(defaultValue = "descending") String sortDirection,
                                                    @RequestParam(required = false) List<Integer> seriesIds,
                                                    @RequestParam(defaultValue = "false") boolean includeSeries) {
        List<Integer> series = seriesIds == null || seriesIds.isEmpty() ? null : seriesIds;
        return new PagingResource<>(page, pageSize, sortKey, sortDirection, (int) blocklist.count(series),
                resources.blocklist(blocklist.page(page, Math.max(1, pageSize), series), includeSeries));
    }

    /**
     * Removes an entry: the release may be grabbed again.
     *
     * @param id the entry
     * @param principal who
     * @param request the request
     * @return nothing
     */
    @Operation(summary = "Remove an entry")
    @DeleteMapping("/api/v3/blocklist/{id}")
    public Map<String, Object> delete(@PathVariable int id, Principal principal, HttpServletRequest request) {
        blocklist.delete(List.of(id), RequestActor.of(principal, request));
        return Map.of();
    }

    /**
     * Removes entries.
     *
     * @param body {@code {"ids": [...]}}
     * @param principal who
     * @param request the request
     * @return nothing
     */
    @Operation(summary = "Remove entries")
    @DeleteMapping("/api/v3/blocklist/bulk")
    public Map<String, Object> deleteBulk(@RequestBody Map<String, List<Integer>> body, Principal principal,
                                          HttpServletRequest request) {
        blocklist.delete(body.getOrDefault("ids", List.of()), RequestActor.of(principal, request));
        return Map.of();
    }
}
