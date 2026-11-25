package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.PagingResource;
import com.catacumbas69.catasonarr.dto.QueueStatusResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/queue}, empty until phase 3 brings the download clients (orch.md decision 120): Seerr
 * asks for it every minute and other clients when they start, and a 404 reads to them as a broken Sonarr.
 */
@Tag(name = "Queue (Sonarr API v3)")
@RestController
public class QueueController {

    private static final QueueStatusResource EMPTY = new QueueStatusResource(0, 0, 0, 0, false, false, false,
            false);

    /**
     * The queue, a page at a time; Sonarr sorts it by the time left by default.
     *
     * @param page the page, from 1
     * @param pageSize records per page
     * @param sortKey what to sort by
     * @param sortDirection {@code ascending}, {@code descending} or {@code default}
     * @return an empty page
     */
    @Operation(summary = "The queue (empty until the download clients, phase 3)")
    @GetMapping("/api/v3/queue")
    public PagingResource<Object> queue(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int pageSize,
                                        @RequestParam(defaultValue = "timeleft") String sortKey,
                                        @RequestParam(defaultValue = "ascending") String sortDirection) {
        return new PagingResource<>(page, pageSize, sortKey, sortDirection, 0, List.of());
    }

    /**
     * The whole queue, without pages.
     *
     * @return no downloads
     */
    @Operation(summary = "The whole queue (empty until the download clients, phase 3)")
    @GetMapping("/api/v3/queue/details")
    public List<Object> details() {
        return List.of();
    }

    /**
     * How many downloads there are, and whether any has an error or a warning.
     *
     * @return all zero
     */
    @Operation(summary = "The queue's counts (zero until the download clients, phase 3)")
    @GetMapping("/api/v3/queue/status")
    public QueueStatusResource status() {
        return EMPTY;
    }
}
