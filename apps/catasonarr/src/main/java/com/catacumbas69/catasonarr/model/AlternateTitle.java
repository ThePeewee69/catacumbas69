package com.catacumbas69.catasonarr.model;

/**
 * Another title of a series (TVmaze's "akas").
 *
 * @param title the title
 * @param seasonNumber the season it applies to, or {@code null} for the whole series
 */
public record AlternateTitle(String title, Integer seasonNumber) {
}
