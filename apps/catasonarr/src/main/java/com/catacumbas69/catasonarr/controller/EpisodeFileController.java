package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.media.EpisodeImporter;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.EpisodeFileService;
import com.catacumbas69.catasonarr.service.SeriesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/episodefile} (orch.md decision 122): the episodes' files, with quality, custom formats and
 * MediaInfo; deleting one sends it to the recycle bin. Bazarr reads them.
 */
@Tag(name = "Episode files (Sonarr API v3)")
@RestController
@RequestMapping("/api/v3/episodefile")
public class EpisodeFileController {

    private final EpisodeFileService files;
    private final SeriesService series;
    private final ActivityResources resources;
    private final EpisodeImporter importer;

    /**
     * Creates the controller.
     *
     * @param files the files
     * @param series the series
     * @param resources the resources
     * @param importer deletes a file
     */
    public EpisodeFileController(EpisodeFileService files, SeriesService series, ActivityResources resources,
                                 EpisodeImporter importer) {
        this.files = files;
        this.series = series;
        this.resources = resources;
        this.importer = importer;
    }

    /**
     * A series' files, or files by id; one of the two is needed.
     *
     * @param seriesId the series
     * @param episodeFileIds these files
     * @return the files
     */
    @Operation(summary = "A series' files, or files by id")
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Integer seriesId,
                                          @RequestParam(required = false) List<Integer> episodeFileIds) {
        List<EpisodeFile> found;
        if (seriesId != null) {
            found = files.bySeries(seriesId);
        } else if (episodeFileIds != null && !episodeFileIds.isEmpty()) {
            found = files.byIds(episodeFileIds);
        } else {
            throw new BadRequestException("seriesId", "seriesId or episodeFileIds must be provided");
        }
        Map<Integer, Series> bySeries = new HashMap<>();
        return found.stream().map(f -> resources.episodeFile(f, bySeries.computeIfAbsent(f.seriesId(),
                series::get))).toList();
    }

    /**
     * One file.
     *
     * @param id the id
     * @return the file
     */
    @Operation(summary = "One file")
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable int id) {
        EpisodeFile f = files.get(id);
        return resources.episodeFile(f, series.get(f.seriesId()));
    }

    /**
     * Deletes a file: to the recycle bin, with its subtitles and extras.
     *
     * @param id the id
     * @param principal who
     * @param request the request
     * @return nothing
     */
    @Operation(summary = "Delete a file (to the recycle bin)")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable int id, Principal principal, HttpServletRequest request) {
        delete(List.of(id), RequestActor.of(principal, request));
        return Map.of();
    }

    /**
     * Deletes files.
     *
     * @param body {@code {"episodeFileIds": [...]}}
     * @param principal who
     * @param request the request
     * @return nothing
     */
    @Operation(summary = "Delete files (to the recycle bin)")
    @DeleteMapping("/bulk")
    public Map<String, Object> deleteBulk(@RequestBody Map<String, List<Integer>> body, Principal principal,
                                          HttpServletRequest request) {
        delete(body.getOrDefault("episodeFileIds", List.of()), RequestActor.of(principal, request));
        return Map.of();
    }

    private void delete(List<Integer> ids, String actor) {
        for (int id : ids) {
            EpisodeFile f = files.get(id);
            try {
                importer.delete(series.get(f.seriesId()), f, actor);
            } catch (IOException e) {
                throw new BadRequestException("episodeFileId", "Unable to delete " + f.relativePath() + ": "
                        + e.getMessage());
            }
        }
    }
}
