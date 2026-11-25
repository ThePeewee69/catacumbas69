package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.ConnectionsResponse;
import com.catacumbas69.catasonarr.dto.HealthCheckResponse;
import com.catacumbas69.catasonarr.service.ConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catasonarr's own {@code /api/v1/connections} (orch.md decision 119), for its Settings page: where it connects,
 * without MongoDB's password, and a health check of each. Behind the Dex session; Sonarr's API key does not reach it.
 */
@Tag(name = "Connections (Catasonarr)")
@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionsController {

    private final ConnectionService connections;

    /**
     * Creates the controller.
     *
     * @param connections the connections
     */
    public ConnectionsController(ConnectionService connections) {
        this.connections = connections;
    }

    /**
     * Where Catasonarr connects.
     *
     * @return Pulsar's addresses and MongoDB's connection, without its password
     */
    @Operation(summary = "Pulsar's addresses and MongoDB's connection, without its password")
    @GetMapping
    public ConnectionsResponse connections() {
        return connections.connections();
    }

    /**
     * Checks Pulsar.
     *
     * @return UP with the broker's version, or DOWN with why
     */
    @Operation(summary = "Health check of Pulsar (admin API)")
    @GetMapping("/pulsar/health")
    public HealthCheckResponse pulsar() {
        return connections.checkPulsar();
    }

    /**
     * Checks MongoDB.
     *
     * @return UP with the server's version, or DOWN with why
     */
    @Operation(summary = "Health check of MongoDB (ping)")
    @GetMapping("/mongodb/health")
    public HealthCheckResponse mongodb() {
        return connections.checkMongo();
    }
}
