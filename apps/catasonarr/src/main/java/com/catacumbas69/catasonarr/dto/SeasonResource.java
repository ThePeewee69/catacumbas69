package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Sonarr's {@code SeasonResource}.
 *
 * @param seasonNumber 0 for specials
 * @param monitored whether it is monitored
 * @param statistics its statistics; {@code null} in a lookup
 * @param images its images, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeasonResource(int seasonNumber, boolean monitored, SeasonStatisticsResource statistics,
                             List<MediaCoverResource> images) {
}
