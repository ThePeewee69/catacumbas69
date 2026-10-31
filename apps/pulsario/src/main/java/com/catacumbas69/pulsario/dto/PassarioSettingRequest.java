package com.catacumbas69.pulsario.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * New Passário base URL.
 *
 * @param url absolute {@code http} or {@code https} URL, e.g. {@code http://localhost:8082}
 */
public record PassarioSettingRequest(@NotBlank @Size(max = 2048) String url) {
}
