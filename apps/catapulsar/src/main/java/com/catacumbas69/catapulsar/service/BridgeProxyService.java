package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Backend-for-frontend for bridges (ARCH-2, ADR 0001): forwards bridge calls from the browser to Cataponte
 * at the URL held by {@link CataponteEndpoint}.
 *
 * <p>Cataponte owns validation, the bridge model and the audit of bridge changes (it records before and
 * after in {@code audit_logs}), so bodies are passed through unchanged, including Cataponte's Problem
 * Details on errors. The browser's address is sent as {@code X-Forwarded-For} so Cataponte can record who
 * made the change. If Cataponte cannot be reached a {@link CataponteUnavailableException} is thrown, which
 * the API maps to {@code 503} (RES-12).
 *
 * <p>Every forwarded change is also published as a {@code bridge.<action>-requested} event on
 * {@code catacumbas69/catapulsar/events} with Cataponte's HTTP status, refused requests included
 * (ADR 0008). Cataponte publishes its own {@code bridge.<action>} event for the change it made.
 */
@Service
public class BridgeProxyService {

    /** Header that carries the browser's address to Cataponte, for its audit log. */
    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final Logger LOG = LoggerFactory.getLogger(BridgeProxyService.class);
    private static final String BRIDGES = "/api/v1/bridges";
    private static final int UNAVAILABLE = 503;

    private final RestClient cataponte;
    private final CataponteEndpoint endpoint;
    private final EventPublisher events;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param cataponte HTTP client for Cataponte (timeouts only; the URL comes from {@code endpoint})
     * @param endpoint Cataponte base URL
     * @param events publishes the request events
     * @param objectMapper reads bridge ids and names from request and response bodies
     */
    public BridgeProxyService(@Qualifier("cataponteRestClient") RestClient cataponte, CataponteEndpoint endpoint,
                              EventPublisher events, ObjectMapper objectMapper) {
        this.cataponte = cataponte;
        this.endpoint = endpoint;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists bridges.
     *
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> list() {
        return forward(HttpMethod.GET, BRIDGES, null, null);
    }

    /**
     * Returns the runtime status of the bridges.
     *
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> status() {
        return forward(HttpMethod.GET, BRIDGES + "/status", null, null);
    }

    /**
     * Returns the all-time message counts of every bridge, per topic.
     *
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> traffic() {
        return forward(HttpMethod.GET, BRIDGES + "/traffic", null, null);
    }

    /**
     * Returns the all-time message counts of one bridge, per topic.
     *
     * @param id bridge id
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> traffic(String id) {
        return forward(HttpMethod.GET, BRIDGES + "/" + id + "/traffic", null, null);
    }

    /**
     * Returns the per-minute message counts of one bridge.
     *
     * @param id bridge id
     * @param from first minute, or {@code null} for Cataponte's default
     * @param to end of the range, or {@code null} for now
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> trafficMinutes(String id, Instant from, Instant to) {
        UriComponentsBuilder path = UriComponentsBuilder.fromPath(BRIDGES + "/" + id + "/traffic/minutes");
        if (from != null) {
            path.queryParam("from", from);
        }
        if (to != null) {
            path.queryParam("to", to);
        }
        return forward(HttpMethod.GET, path.build().toUriString(), null, null);
    }

    /**
     * Returns one bridge.
     *
     * @param id bridge id
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> get(String id) {
        return forward(HttpMethod.GET, BRIDGES + "/" + id, null, null);
    }

    /**
     * Creates a bridge.
     *
     * @param body bridge definition as JSON
     * @param user client IP address, recorded by Cataponte
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> create(String body, String user) {
        return requested(AuditAction.CREATE_BRIDGE, null, body, user,
                () -> forward(HttpMethod.POST, BRIDGES, body, user));
    }

    /**
     * Replaces a bridge definition.
     *
     * @param id bridge id
     * @param body bridge definition as JSON
     * @param user client IP address, recorded by Cataponte
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> update(String id, String body, String user) {
        return requested(AuditAction.UPDATE_BRIDGE, id, body, user,
                () -> forward(HttpMethod.PUT, BRIDGES + "/" + id, body, user));
    }

    /**
     * Deletes a bridge.
     *
     * @param id bridge id
     * @param user client IP address, recorded by Cataponte
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> delete(String id, String user) {
        return requested(AuditAction.DELETE_BRIDGE, id, null, user,
                () -> forward(HttpMethod.DELETE, BRIDGES + "/" + id, null, user));
    }

    /**
     * Enables or disables a bridge.
     *
     * @param id bridge id
     * @param enabled desired state
     * @param user client IP address, recorded by Cataponte
     * @return Cataponte's response
     */
    public ResponseEntity<byte[]> setEnabled(String id, boolean enabled, String user) {
        return requested(enabled ? AuditAction.ENABLE_BRIDGE : AuditAction.DISABLE_BRIDGE, id, null, user,
                () -> forward(HttpMethod.POST, BRIDGES + "/" + id + (enabled ? "/enable" : "/disable"), null, user));
    }

    /** Forwards a change and publishes the matching request event, whatever Cataponte answered. */
    private ResponseEntity<byte[]> requested(AuditAction action, String id, String body, String user,
                                             Supplier<ResponseEntity<byte[]>> call) {
        ResponseEntity<byte[]> response;
        try {
            response = call.get();
        } catch (CataponteUnavailableException e) {
            publishRequested(action, id, body, user, UNAVAILABLE, null);
            throw e;
        }
        publishRequested(action, id, body, user, response.getStatusCode().value(), response.getBody());
        return response;
    }

    private void publishRequested(AuditAction action, String id, String body, String user, int status,
                                  byte[] responseBody) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", status);
            if (body != null) {
                details.put("request", body);
            }
            String subject = Optional.ofNullable(id)
                    .or(() -> field(responseBody, "id"))
                    .or(() -> field(body == null ? null : body.getBytes(StandardCharsets.UTF_8), "name"))
                    .orElse("unknown");
            events.publish(action.entity(), action.verb(), subject, EventData.action(user, details));
        } catch (RuntimeException e) {
            LOG.warn("Event not published: {} {}: {}", action, id, e.getMessage());
        }
    }

    private Optional<String> field(byte[] json, String name) {
        if (json == null || json.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode value = objectMapper.readTree(json).path(name);
            return value.isTextual() ? Optional.of(value.asText()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private ResponseEntity<byte[]> forward(HttpMethod method, String path, String body, String user) {
        try {
            RestClient.RequestBodySpec request = cataponte.method(method).uri(endpoint.url() + path)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
            if (user != null) {
                request.header(FORWARDED_FOR, user);
            }
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((req, response) -> {
                HttpHeaders headers = new HttpHeaders();
                MediaType contentType = response.getHeaders().getContentType();
                if (contentType != null) {
                    headers.setContentType(contentType);
                }
                byte[] responseBody = response.getBody().readAllBytes();
                return ResponseEntity.status(response.getStatusCode()).headers(headers).body(responseBody);
            });
        } catch (ResourceAccessException e) {
            throw new CataponteUnavailableException(e.getCause() instanceof IOException ? e.getCause() : e);
        }
    }
}
