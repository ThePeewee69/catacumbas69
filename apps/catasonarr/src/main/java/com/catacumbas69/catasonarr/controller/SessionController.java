package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.SessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who is signed in, for the page (SEC-2, orch.md decision 109).
 */
@Tag(name = "Session")
@RestController
public class SessionController {

    /**
     * The signed-in user.
     *
     * @param principal the signed-in user; {@code null} without login (profile {@code no-auth})
     * @return the user's name and whether there is a login at all
     */
    @Operation(summary = "The signed-in user")
    @GetMapping("/api/v1/session")
    public SessionResponse session(Principal principal) {
        return principal == null ? new SessionResponse(null, false) : new SessionResponse(principal.getName(), true);
    }
}
