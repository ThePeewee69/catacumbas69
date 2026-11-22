package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Sonarr's validation failure: a {@code 400} answers a list of them.
 *
 * @param propertyName the field at fault, or {@code null}
 * @param errorMessage why
 * @param severity {@code error}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationFailure(String propertyName, String errorMessage, String severity) {
}
