package com.catacumbas69.catasonarr.dto;

/**
 * The answer of {@code GET /api/v1/session} (SEC-2).
 *
 * @param user the user's name (Dex's {@code preferred_username}), or {@code null} without login
 * @param login whether Catasonarr runs with login, so whether the page shows the user and Sign out
 */
public record SessionResponse(String user, boolean login) {
}
