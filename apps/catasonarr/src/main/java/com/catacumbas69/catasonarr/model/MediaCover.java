package com.catacumbas69.catasonarr.model;

/**
 * An image of a series, season or episode, by its address at TVmaze or TMDB (orch.md decision 112: Catasonarr keeps
 * no copy). Sonarr's {@code url} is the same address.
 *
 * @param coverType {@code poster}, {@code banner}, {@code fanart}, {@code screenshot}…
 * @param remoteUrl the image's address
 */
public record MediaCover(String coverType, String remoteUrl) {
}
