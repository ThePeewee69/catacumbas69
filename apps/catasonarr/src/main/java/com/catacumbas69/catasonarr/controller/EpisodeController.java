package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.EpisodeResource;
import com.catacumbas69.catasonarr.dto.EpisodesMonitoredResource;
import com.catacumbas69.catasonarr.dto.SeriesResource;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.EpisodeService;
import com.catacumbas69.catasonarr.service.SeriesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/episode} (orch.md decision 112).
 */
@Tag(name = "Episodes (Sonarr API v3)")
@RestController
@RequestMapping("/api/v3/episode")
public class EpisodeController {

    private final EpisodeService episodes;
    private final SeriesService series;

    /**
     * Creates the controller.
     *
     * @param episodes episodes
     * @param series the series, for {@code includeSeries}
     */
    public EpisodeController(EpisodeService episodes, SeriesService series) {
        this.episodes = episodes;
        this.series = series;
    }

    /**
     * A series' episodes, or episodes by id; one of the two is needed, as in Sonarr.
     *
     * @param seriesId the series
     * @param seasonNumber only this season
     * @param episodeIds these episodes
     * @param includeSeries include each episode's series
     * @return the episodes
     */
    @Operation(summary = "Episodes of a series, or by id")
    @GetMapping
    public List<EpisodeResource> list(@RequestParam(required = false) Integer seriesId,
                                      @RequestParam(required = false) Integer seasonNumber,
                                      @RequestParam(required = false) List<Integer> episodeIds,
                                      @RequestParam(defaultValue = "false") boolean includeSeries) {
        List<Episode> found;
        if (seriesId != null) {
            series.get(seriesId);
            found = episodes.bySeries(seriesId, seasonNumber);
        } else if (episodeIds != null && !episodeIds.isEmpty()) {
            found = episodes.byIds(episodeIds);
        } else {
            throw new BadRequestException("seriesId", "seriesId or episodeIds must be provided");
        }
        Map<Integer, SeriesResource> seriesById = new HashMap<>();
        return found.stream().map(e -> SonarrResources.episode(e, includeSeries
                ? seriesById.computeIfAbsent(e.seriesId(), id -> SonarrResources.series(series.get(id), null))
                : null)).toList();
    }

    /**
     * One episode.
     *
     * @param id the id
     * @return the episode
     */
    @Operation(summary = "One episode")
    @GetMapping("/{id}")
    public EpisodeResource get(@PathVariable int id) {
        return SonarrResources.episode(episodes.get(id), null);
    }

    /**
     * Changes an episode: only whether it is monitored, as in Sonarr.
     *
     * @param id the id
     * @param body the episode
     * @param principal who
     * @param request the request
     * @return {@code 202} with the episode
     */
    @Operation(summary = "Monitor or unmonitor an episode")
    @PutMapping("/{id}")
    public ResponseEntity<EpisodeResource> update(@PathVariable int id, @RequestBody EpisodeResource body,
                                                  Principal principal, HttpServletRequest request) {
        episodes.get(id);
        Episode saved = episodes.setMonitored(List.of(id), body.monitored(), RequestActor.of(principal, request))
                .get(0);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(SonarrResources.episode(saved, null));
    }

    /**
     * Monitors or unmonitors episodes.
     *
     * @param body the episodes and the flag
     * @param principal who
     * @param request the request
     * @return {@code 202} with the episodes
     */
    @Operation(summary = "Monitor or unmonitor episodes")
    @PutMapping("/monitor")
    public ResponseEntity<List<EpisodeResource>> monitor(@RequestBody EpisodesMonitoredResource body,
                                                         Principal principal, HttpServletRequest request) {
        List<Integer> ids = body.episodeIds() == null ? List.of() : body.episodeIds();
        List<EpisodeResource> saved = episodes.setMonitored(ids, body.monitored(), RequestActor.of(principal, request))
                .stream().map(e -> SonarrResources.episode(e, null)).toList();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(saved);
    }
}
