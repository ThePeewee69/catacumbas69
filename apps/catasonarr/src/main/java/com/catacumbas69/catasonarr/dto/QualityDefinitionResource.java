package com.catacumbas69.catasonarr.dto;

import com.catacumbas69.catasonarr.quality.Quality;

/**
 * Sonarr's {@code QualityDefinitionResource}: sizes in MB per minute.
 *
 * @param id the id
 * @param quality the quality
 * @param title the name shown
 * @param weight the order
 * @param minSize minimum
 * @param maxSize maximum, {@code null} for none
 * @param preferredSize preferred
 */
public record QualityDefinitionResource(int id, Quality quality, String title, int weight, Double minSize,
                                        Double maxSize, Double preferredSize) {
}
