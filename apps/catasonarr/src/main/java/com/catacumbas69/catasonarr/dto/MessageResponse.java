package com.catacumbas69.catasonarr.dto;

/**
 * An error as Sonarr answers it outside validation: {@code {"message": "…"}}.
 *
 * @param message what went wrong
 */
public record MessageResponse(String message) {
}
