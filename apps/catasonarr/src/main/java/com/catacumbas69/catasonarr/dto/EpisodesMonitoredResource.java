package com.catacumbas69.catasonarr.dto;

import java.util.List;

/**
 * Sonarr's {@code EpisodesMonitoredResource}: {@code PUT /api/v3/episode/monitor}.
 *
 * @param episodeIds the episodes
 * @param monitored the flag
 */
public record EpisodesMonitoredResource(List<Integer> episodeIds, boolean monitored) {
}
