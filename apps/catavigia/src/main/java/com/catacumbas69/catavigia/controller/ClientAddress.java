package com.catacumbas69.catavigia.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who made a request: the first {@code X-Forwarded-For} address (set by Catapulsar), else the remote address.
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
