package com.catacumbas69.catasonarr.dto;

import com.catacumbas69.catasonarr.quality.Quality;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Sonarr's {@code QualityProfileResource}.
 *
 * @param id the id
 * @param name the name
 * @param upgradeAllowed whether upgrades are allowed
 * @param cutoff the quality or group id where upgrades stop
 * @param items the qualities and groups, worst first
 * @param minFormatScore lowest custom format score
 * @param cutoffFormatScore custom format score where upgrades stop
 * @param minUpgradeFormatScore least gain for an upgrade
 * @param formatItems custom format scores
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QualityProfileResource(int id, String name, boolean upgradeAllowed, int cutoff, List<Item> items,
                                     int minFormatScore, int cutoffFormatScore, int minUpgradeFormatScore,
                                     List<FormatItem> formatItems) {

    /**
     * Sonarr's {@code QualityProfileQualityItemResource}: one quality or a group.
     *
     * @param id the group's id, 0 for a quality
     * @param name the group's name, or {@code null}
     * @param quality the quality, or {@code null} for a group
     * @param items the group's qualities
     * @param allowed whether it is allowed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(int id, String name, Quality quality, List<Item> items, boolean allowed) {
    }

    /**
     * Sonarr's {@code ProfileFormatItemResource}.
     *
     * @param format the custom format's id
     * @param name its name
     * @param score its score
     */
    public record FormatItem(int format, String name, int score) {
    }
}
