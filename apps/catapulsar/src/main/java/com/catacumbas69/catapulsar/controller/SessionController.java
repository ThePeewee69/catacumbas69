package com.catacumbas69.catapulsar.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who is signed in, for the top bar (SEC-2, orch.md decision 100).
 */
@Tag(name = "Session")
@RestController
public class SessionController {

    /**
     * The signed-in user, as the page shows it and as the audit records it.
     *
     * @param principal the signed-in user; {@code null} without login (profile {@code no-auth})
     * @return the user's name and whether there is a login at all
     */
    @Operation(summary = "The signed-in user")
    @GetMapping("/api/v1/session")
    public SessionResponse session(Principal principal) {
        return principal == null ? new SessionResponse(null, false) : new SessionResponse(principal.getName(), true);
    }

    /**
     * The answer of {@code GET /api/v1/session}.
     *
     * @param user the user's name (Dex's {@code preferred_username}), or {@code null} without login
     * @param login whether Catapulsar runs with login, so whether the page shows the user and "Sair"
     */
    public record SessionResponse(String user, boolean login) {
    }
}
