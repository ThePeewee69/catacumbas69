package com.catacumbas69.catasonarr.model;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A quality profile, as Sonarr keeps it (orch.md decision 112): imported from Sonarr's database with Sonarr's ids,
 * or Sonarr's six defaults until then.
 *
 * @param id Sonarr's id
 * @param name unique name
 * @param upgradeAllowed whether better releases replace the file
 * @param cutoff the quality or group id where upgrades stop
 * @param items the qualities, worst first
 * @param minFormatScore the lowest custom format score accepted
 * @param cutoffFormatScore the custom format score where upgrades stop
 * @param minUpgradeFormatScore the least custom format score gain for an upgrade
 * @param formatItems each custom format's score
 */
@Document("catasonarr_qualityprofiles")
public record QualityProfile(
        @Id int id,
        String name,
        boolean upgradeAllowed,
        int cutoff,
        List<ProfileItem> items,
        int minFormatScore,
        int cutoffFormatScore,
        int minUpgradeFormatScore,
        List<FormatItem> formatItems) {
}
