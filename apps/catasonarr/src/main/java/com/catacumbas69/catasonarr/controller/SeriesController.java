package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.SeriesResource;
import com.catacumbas69.catasonarr.metadata.MetadataService;
import com.catacumbas69.catasonarr.metadata.SeriesInfo;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.CommandService;
import com.catacumbas69.catasonarr.service.EpisodeService;
import com.catacumbas69.catasonarr.service.SeriesService;
import com.catacumbas69.catasonarr.service.Statistics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/series} (orch.md decision 112): what Overseerr and Jellyseerr call to find, add and change
 * series, and Bazarr and Homarr to list them.
 */
@Tag(name = "Series (Sonarr API v3)")
@RestController
@RequestMapping("/api/v3/series")
public class SeriesController {

    private final SeriesService series;
    private final EpisodeService episodes;
    private final MetadataService metadata;
    private final CommandService commands;

    /**
     * Creates the controller.
     *
     * @param series the series
     * @param episodes episodes, for the statistics
     * @param metadata TVmaze and TMDB, for the lookup
     * @param commands commands, for the search asked when a series is added
     */
    public SeriesController(SeriesService series, EpisodeService episodes, MetadataService metadata,
                            CommandService commands) {
        this.series = series;
        this.episodes = episodes;
        this.metadata = metadata;
        this.commands = commands;
    }

    /**
     * Every series, or the one with a TheTVDB id.
     *
     * @param tvdbId only this one, or {@code null}
     * @return the series
     */
    @Operation(summary = "Every series")
    @GetMapping
    public List<SeriesResource> all(@RequestParam(required = false) Integer tvdbId) {
        List<Series> list = tvdbId == null ? series.all() : series.byTvdb(tvdbId).map(List::of).orElse(List.of());
        Map<Integer, Map<Integer, Statistics>> stats = episodes.statistics(
                tvdbId == null ? null : list.stream().map(Series::id).toList());
        return list.stream().map(s -> SonarrResources.series(s, stats.get(s.id()))).toList();
    }

    /**
     * One series.
     *
     * @param id the id
     * @return the series
     */
    @Operation(summary = "One series")
    @GetMapping("/{id}")
    public SeriesResource get(@PathVariable int id) {
        return resource(series.get(id));
    }

    /**
     * Sonarr's lookup: {@code tvdb:<id>}, {@code imdb:<id>} or words. A series already in the library comes back as
     * it is there, with its id.
     *
     * @param term what was typed
     * @return the series found
     */
    @Operation(summary = "Find series in TVmaze (tvdb:<id>, imdb:<id> or words)")
    @GetMapping("/lookup")
    public List<SeriesResource> lookup(@RequestParam(defaultValue = "") String term) {
        List<SeriesResource> found = new ArrayList<>();
        Map<Integer, Series> library = series.all().stream()
                .collect(Collectors.toMap(Series::tvdbId, s -> s, (a, b) -> a));
        for (SeriesInfo info : metadata.lookup(term)) {
            Series existing = library.get(info.tvdbId());
            found.add(existing != null ? resource(existing)
                    : SonarrResources.lookup(info, series.folderName(info)));
        }
        return found;
    }

    /**
     * Adds a series. Its folder is then scanned and the search asked in {@code addOptions} runs, as in Sonarr
     * (orch.md decision 122).
     *
     * @param body Sonarr's series, with {@code tvdbId}, {@code qualityProfileId}, {@code rootFolderPath} or
     *             {@code path}, the seasons and {@code addOptions}
     * @param principal who
     * @param request the request
     * @return {@code 201} with the series
     */
    @Operation(summary = "Add a series")
    @PostMapping
    public ResponseEntity<SeriesResource> add(@RequestBody SeriesResource body, Principal principal,
                                              HttpServletRequest request) {
        Series added = series.add(new SeriesService.AddRequest(body.tvdbId(), body.qualityProfileId(), body.path(),
                body.rootFolderPath(), body.seasonFolder(), body.monitored(), body.monitorNewItems(),
                body.seriesType(), body.tags(), SonarrResources.seasons(body.seasons()), body.addOptions()),
                RequestActor.of(principal, request));
        commands.seriesAdded(added, body.addOptions(), RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.CREATED).body(resource(added));
    }

    /**
     * Changes a series, by the id in the address.
     *
     * @param id the id
     * @param moveFiles must be {@code false}: moving files comes with the import
     * @param body the series
     * @param principal who
     * @param request the request
     * @return {@code 202} with the series
     */
    @Operation(summary = "Change a series")
    @PutMapping("/{id}")
    public ResponseEntity<SeriesResource> update(@PathVariable int id,
                                                 @RequestParam(defaultValue = "false") boolean moveFiles,
                                                 @RequestBody SeriesResource body, Principal principal,
                                                 HttpServletRequest request) {
        if (moveFiles) {
            throw new BadRequestException("moveFiles", "Moving files is not done yet (comes with the import)");
        }
        Series updated = series.update(id, new SeriesService.Change(body.qualityProfileId(), body.path(),
                body.seasonFolder(), body.monitored(), body.monitorNewItems(), body.seriesType(),
                body.useSceneNumbering(), body.tags(), body.seasons() == null ? null
                : SonarrResources.seasons(body.seasons())), RequestActor.of(principal, request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resource(updated));
    }

    /**
     * Changes a series, by the id in the body, as Overseerr calls it.
     *
     * @param moveFiles must be {@code false}
     * @param body the series, with its id
     * @param principal who
     * @param request the request
     * @return {@code 202} with the series
     */
    @Operation(summary = "Change a series (id in the body)")
    @PutMapping
    public ResponseEntity<SeriesResource> updateById(@RequestParam(defaultValue = "false") boolean moveFiles,
                                                     @RequestBody SeriesResource body, Principal principal,
                                                     HttpServletRequest request) {
        return update(body.id(), moveFiles, body, principal, request);
    }

    /**
     * Deletes a series; with {@code deleteFiles=true} its folder on the NAS too, as Sonarr.
     *
     * @param id the id
     * @param deleteFiles delete the folder
     * @param addImportListExclusion ignored: no import lists yet
     * @param principal who
     * @param request the request
     * @return an empty object, as Sonarr
     */
    @Operation(summary = "Delete a series (deleteFiles=true deletes its folder)")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable int id, @RequestParam(defaultValue = "false") boolean deleteFiles,
                                      @RequestParam(defaultValue = "false") boolean addImportListExclusion,
                                      Principal principal, HttpServletRequest request) {
        series.delete(id, deleteFiles, RequestActor.of(principal, request));
        return Map.of();
    }

    private SeriesResource resource(Series s) {
        Map<Integer, Map<Integer, Statistics>> stats = episodes.statistics(List.of(s.id()));
        return SonarrResources.series(s, Optional.ofNullable(stats.get(s.id())).orElse(Map.of()));
    }
}
