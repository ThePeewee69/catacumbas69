package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catacommons.audit.Actor;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

/**
 * Who made a request, for the audit events and for the other apps (orch.md decision 100): the signed-in user
 * (Dex's {@code preferred_username}); without login (profile {@code no-auth}) the browser's address, from
 * {@code X-Forwarded-For} set by the router. A {@code X-Catacumbas69-User} sent by the browser is never trusted here.
 */
final class RequestActor {

    private RequestActor() {
    }

    static String of(HttpServletRequest request) {
        Principal user = request.getUserPrincipal();
        if (user != null && user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return Actor.resolve(null, request.getHeader("X-Forwarded-For"), request.getRemoteAddr());
    }
}
