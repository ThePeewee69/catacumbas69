package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Sonarr's {@code AlternateTitleResource}.
 *
 * @param title the title
 * @param seasonNumber the season it applies to, or {@code null}
 * @param sceneSeasonNumber always {@code null}: no scene mapping yet
 * @param sceneOrigin where it comes from: {@code tvmaze}
 * @param comment {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlternateTitleResource(String title, Integer seasonNumber, Integer sceneSeasonNumber,
                                     String sceneOrigin, String comment) {
}
