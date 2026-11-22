package com.catacumbas69.catasonarr.model;

/**
 * One of Sonarr's languages, by Sonarr's id (see {@link Languages}).
 *
 * @param id Sonarr's id, e.g. 1 for English, 18 for Portuguese
 * @param name the name, e.g. {@code English}
 */
public record Language(int id, String name) {
}
