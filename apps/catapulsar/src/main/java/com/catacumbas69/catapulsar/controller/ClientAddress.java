package com.catacumbas69.catapulsar.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client IP recorded in the audit log, honouring {@code X-Forwarded-For} set by the
 * ingress/route.
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
