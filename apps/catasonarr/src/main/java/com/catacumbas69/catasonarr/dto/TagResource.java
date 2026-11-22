package com.catacumbas69.catasonarr.dto;

/**
 * Sonarr's {@code TagResource}.
 *
 * @param id the id
 * @param label the label, in lower case
 */
public record TagResource(int id, String label) {
}
