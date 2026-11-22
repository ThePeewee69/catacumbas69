package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.CommandResource;
import com.catacumbas69.catasonarr.service.CommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/command} (orch.md decision 112): {@code RefreshSeries} runs; the searches are accepted and
 * kept, and end unsuccessful until phase 2.
 */
@Tag(name = "Commands (Sonarr API v3)")
@RestController
@RequestMapping("/api/v3/command")
public class CommandController {

    private final CommandService commands;

    /**
     * Creates the controller.
     *
     * @param commands commands
     */
    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    /**
     * Sends a command.
     *
     * @param body {@code {"name": "RefreshSeries", "seriesId": 1}} and the like
     * @param principal who
     * @param request the request
     * @return {@code 201} with the command
     */
    @Operation(summary = "Send a command")
    @PostMapping
    public ResponseEntity<CommandResource> submit(@RequestBody Map<String, Object> body, Principal principal,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SonarrResources.command(commands.submit(body, RequestActor.of(principal, request))));
    }

    /**
     * The commands running or ended in the last hour.
     *
     * @return the commands, newest first
     */
    @Operation(summary = "Recent commands")
    @GetMapping
    public List<CommandResource> recent() {
        return commands.recent().stream().map(SonarrResources::command).toList();
    }

    /**
     * One command.
     *
     * @param id the id
     * @return the command
     */
    @Operation(summary = "One command")
    @GetMapping("/{id}")
    public CommandResource get(@PathVariable int id) {
        return SonarrResources.command(commands.get(id));
    }
}
