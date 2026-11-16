package com.catacumbas69.cataponte.controller;

import com.catacumbas69.catacommons.audit.Actor;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Who made a request, for the audit events: the user Catapulsar names in {@code X-Catacumbas69-User}, else the
 * first {@code X-Forwarded-For} address, else the caller's own address (orch.md decision 100).
 */
final class RequestActor {

    private RequestActor() {
    }

    static String of(HttpServletRequest request) {
        return Actor.resolve(request.getHeader(Actor.HEADER), request.getHeader("X-Forwarded-For"),
                request.getRemoteAddr());
    }
}
