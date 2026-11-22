package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Sonarr's {@code MediaCover}: {@code url} and {@code remoteUrl} are both the image's address at TVmaze or TMDB.
 *
 * @param coverType {@code poster}, {@code banner}, {@code fanart}, {@code screenshot}…
 * @param url the address
 * @param remoteUrl the address
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaCoverResource(String coverType, String url, String remoteUrl) {
}
