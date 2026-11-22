package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Sonarr's {@code SeriesStatisticsResource}.
 *
 * @param seasonCount seasons other than the specials
 * @param episodeFileCount episodes with a file
 * @param episodeCount monitored episodes that aired, or with a file
 * @param totalEpisodeCount every episode
 * @param sizeOnDisk bytes
 * @param releaseGroups release groups of the files
 * @param percentOfEpisodes files over the counted episodes, 0 to 100
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeriesStatisticsResource(int seasonCount, int episodeFileCount, int episodeCount,
                                       int totalEpisodeCount, long sizeOnDisk, List<String> releaseGroups,
                                       double percentOfEpisodes) {
}
