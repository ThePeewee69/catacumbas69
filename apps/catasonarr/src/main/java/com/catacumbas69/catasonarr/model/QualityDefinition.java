package com.catacumbas69.catasonarr.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The size limits of a quality, in MB per minute of episode, as Sonarr keeps them.
 *
 * @param id Sonarr's id
 * @param quality Sonarr's quality id
 * @param title the name shown
 * @param minSize minimum, or {@code null}
 * @param maxSize maximum, or {@code null} for no limit
 * @param preferredSize preferred, or {@code null}
 */
@Document("catasonarr_qualitydefinitions")
public record QualityDefinition(@Id int id, int quality, String title, Double minSize, Double maxSize,
                                Double preferredSize) {
}
