package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catacommons.audit.Actor;
import com.catacumbas69.catasonarr.config.ApiKeyAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

/**
 * Who made a request, for the {@code actor} of its events (EVT-3): the user signed in through Dex; for Sonarr's API
 * key, {@code api-key@<address>}, so the client is told apart; without a login (profile {@code no-auth}), the address.
 */
public final class RequestActor {

    private RequestActor() {
    }

    /**
     * Resolves the actor.
     *
     * @param principal the signed-in user, or {@code null}
     * @param request the request, for the address
     * @return the actor
     */
    public static String of(Principal principal, HttpServletRequest request) {
        String address = Actor.resolve(null, request.getHeader("X-Forwarded-For"), request.getRemoteAddr());
        if (principal == null) {
            return address;
        }
        if (ApiKeyAuthenticationFilter.PRINCIPAL.equals(principal.getName())) {
            return ApiKeyAuthenticationFilter.PRINCIPAL + "@" + address;
        }
        return principal.getName();
    }
}
