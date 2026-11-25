package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result of a connection check (orch.md decision 119).
 *
 * @param status {@code UP} or {@code DOWN}
 * @param latencyMs how long the check took
 * @param version the server's version, when it answered
 * @param error why it failed, when it did
 * @param checkedAt when it was checked
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthCheckResponse(String status, long latencyMs, String version, String error, String checkedAt) {
}
