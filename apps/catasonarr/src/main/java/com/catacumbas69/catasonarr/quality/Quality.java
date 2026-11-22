package com.catacumbas69.catasonarr.quality;

/**
 * One of Sonarr's qualities, as its API v3 shows it: {@code {id, name, source, resolution}}.
 *
 * @param id Sonarr's quality id (0 to 22; 11 is not used)
 * @param name e.g. {@code WEBDL-1080p}
 * @param source {@code unknown}, {@code television}, {@code televisionRaw}, {@code web}, {@code webRip}, {@code dvd},
 *               {@code bluray} or {@code blurayRaw}
 * @param resolution lines, e.g. {@code 1080}; 0 for Unknown
 */
public record Quality(int id, String name, String source, int resolution) {
}
