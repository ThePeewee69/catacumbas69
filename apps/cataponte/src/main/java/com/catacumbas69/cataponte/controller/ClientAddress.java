package com.catacumbas69.cataponte.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client IP recorded in the audit log. Catapulsar forwards the browser's address in
 * {@code X-Forwarded-For}; direct callers are recorded by their own address.
 */
final class ClientAddress {

    private ClientAddress() {
    }

    static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
