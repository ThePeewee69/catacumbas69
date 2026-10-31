package com.catacumbas69.cataponte.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code POST /api/v1/bridges} and {@code PUT /api/v1/bridges/{id}}.
 *
 * @param name unique name
 * @param description optional free text
 * @param enabled whether the bridge should run; defaults to {@code true}
 * @param source where messages are read from
 * @param destinations where messages are copied to (1..10)
 * @param preserveKey copy the message key; defaults to {@code true}
 * @param preserveProperties copy message properties; defaults to {@code true}
 * @param ordered keep source order; defaults to {@code true} unless the subscription is {@code Shared}
 * @param maxInFlight unconfirmed messages allowed; defaults to the service setting
 * @param version version being edited, for conflict detection on update (RES-9); ignored on create
 */
public record BridgeRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = ValidationPatterns.NAME,
                message = "may contain only letters, digits, '.', '-' and '_'") String name,
        @Size(max = 500) String description,
        Boolean enabled,
        @NotNull @Valid Source source,
        @NotEmpty @Size(max = 10) List<@NotNull @Valid Destination> destinations,
        Boolean preserveKey,
        Boolean preserveProperties,
        Boolean ordered,
        @Min(1) @Max(10_000) Integer maxInFlight,
        Long version) {

    /**
     * Source of a bridge.
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @param subscriptionType Exclusive, Shared, Failover or Key_Shared; defaults to Failover when ordered,
     *                         Shared otherwise
     */
    public record Source(
            @NotBlank @Pattern(regexp = ValidationPatterns.TOPIC,
                    message = "must be a full topic name like persistent://tenant/namespace/topic") String topic,
            @NotBlank @Size(max = 100) @Pattern(regexp = ValidationPatterns.NAME,
                    message = "may contain only letters, digits, '.', '-' and '_'") String subscription,
            @Pattern(regexp = ValidationPatterns.SUBSCRIPTION_TYPE,
                    message = "must be Exclusive, Shared, Failover or Key_Shared") String subscriptionType) {
    }

    /**
     * One destination of a bridge.
     *
     * @param topic fully-qualified topic
     */
    public record Destination(
            @NotBlank @Pattern(regexp = ValidationPatterns.TOPIC,
                    message = "must be a full topic name like persistent://tenant/namespace/topic") String topic) {
    }
}
