package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catapulsar.dto.ConsumerCommandRequest;
import com.catacumbas69.catapulsar.dto.ConsumerStatusResponse;
import com.catacumbas69.catapulsar.dto.DeadLetterRequest;
import com.catacumbas69.catapulsar.service.ConsumerStatusService;
import com.catacumbas69.catapulsar.service.DeadLetterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Consumers panel (ADR 0015): the apps' consumers per replica with pause, resume and check, and the actions on
 * dead-letter topics.
 */
@Tag(name = "Consumers")
@RestController
@RequestMapping("/api/v1")
public class ConsumersController {

    private final ConsumerStatusService consumers;
    private final DeadLetterService deadLetters;

    /**
     * Creates the controller.
     *
     * @param consumers the apps' consumers
     * @param deadLetters actions on dead-letter topics
     */
    public ConsumersController(ConsumerStatusService consumers, DeadLetterService deadLetters) {
        this.consumers = consumers;
        this.deadLetters = deadLetters;
    }

    /**
     * Lists every consumer of every app, per replica.
     *
     * @return the consumers
     */
    @Operation(summary = "The apps' consumers, per replica")
    @GetMapping("/consumers")
    public List<ConsumerStatusResponse> consumers() {
        return consumers.list();
    }

    /**
     * Pauses, resumes or checks consumers; the app does it within seconds and reports it in its status.
     *
     * @param request what and where
     * @param http current request, for the audit log
     */
    @Operation(summary = "Pause, resume or check consumers")
    @PostMapping("/consumers/commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void command(@Valid @RequestBody ConsumerCommandRequest request, HttpServletRequest http) {
        consumers.send(request.app(), request.instance(), request.consumer(), request.action(),
                ClientAddress.of(http));
    }

    /**
     * Sends one dead letter back to the topic it came from.
     *
     * @param request the DLQ and the message
     * @param http current request, for the audit log
     * @return {@code {"to": topic}}
     * @throws PulsarClientException if Pulsar refuses
     */
    @Operation(summary = "Send a dead letter back to its original topic")
    @PostMapping("/dead-letters/send-back")
    public Map<String, String> sendBack(@Valid @RequestBody DeadLetterRequest request, HttpServletRequest http)
            throws PulsarClientException {
        return Map.of("to", deadLetters.sendBack(request.topic(), messageId(request), ClientAddress.of(http)));
    }

    /**
     * Removes one dead letter.
     *
     * @param request the DLQ and the message
     * @param http current request, for the audit log
     * @throws PulsarClientException if Pulsar refuses
     */
    @Operation(summary = "Discard a dead letter")
    @PostMapping("/dead-letters/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@Valid @RequestBody DeadLetterRequest request, HttpServletRequest http)
            throws PulsarClientException {
        deadLetters.discard(request.topic(), messageId(request), ClientAddress.of(http));
    }

    /**
     * Sends every dead letter back to the topic it came from.
     *
     * @param request the DLQ
     * @param http current request, for the audit log
     * @return how many were sent back and left
     * @throws PulsarClientException if Pulsar refuses
     */
    @Operation(summary = "Send every dead letter back")
    @PostMapping("/dead-letters/send-back-all")
    public Map<String, Integer> sendBackAll(@Valid @RequestBody DeadLetterRequest request, HttpServletRequest http)
            throws PulsarClientException {
        return deadLetters.sendBackAll(request.topic(), ClientAddress.of(http));
    }

    /**
     * Removes every dead letter.
     *
     * @param request the DLQ
     * @param http current request, for the audit log
     * @throws PulsarAdminException if Pulsar refuses
     */
    @Operation(summary = "Discard every dead letter")
    @PostMapping("/dead-letters/discard-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discardAll(@Valid @RequestBody DeadLetterRequest request, HttpServletRequest http)
            throws PulsarAdminException {
        deadLetters.discardAll(request.topic(), ClientAddress.of(http));
    }

    private static String messageId(DeadLetterRequest request) {
        if (request.messageId() == null || request.messageId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId is required");
        }
        return request.messageId();
    }
}
