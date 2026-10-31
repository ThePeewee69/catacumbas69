package com.catacumbas69.pulsario.service;

import java.io.IOException;
import java.time.Instant;
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
 * Backend-for-frontend for bridges (ARCH-2, ADR 0001): forwards bridge calls from the browser to Passário
 * at the URL held by {@link PassarioEndpoint}.
 *
 * <p>Passário owns validation, the bridge model and the audit of bridge changes (it records before and
 * after in {@code audit_logs}), so bodies are passed through unchanged, including Passário's Problem
 * Details on errors. The browser's address is sent as {@code X-Forwarded-For} so Passário can record who
 * made the change. If Passário cannot be reached a {@link PassarioUnavailableException} is thrown, which
 * the API maps to {@code 503} (RES-12).
 */
@Service
public class BridgeProxyService {

    /** Header that carries the browser's address to Passário, for its audit log. */
    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final String BRIDGES = "/api/v1/bridges";

    private final RestClient passario;
    private final PassarioEndpoint endpoint;

    /**
     * Creates the service.
     *
     * @param passario HTTP client for Passário (timeouts only; the URL comes from {@code endpoint})
     * @param endpoint Passário base URL
     */
    public BridgeProxyService(@Qualifier("passarioRestClient") RestClient passario, PassarioEndpoint endpoint) {
        this.passario = passario;
        this.endpoint = endpoint;
    }

    /**
     * Lists bridges.
     *
     * @return Passário's response
     */
    public ResponseEntity<byte[]> list() {
        return forward(HttpMethod.GET, BRIDGES, null, null);
    }

    /**
     * Returns the runtime status of the bridges.
     *
     * @return Passário's response
     */
    public ResponseEntity<byte[]> status() {
        return forward(HttpMethod.GET, BRIDGES + "/status", null, null);
    }

    /**
     * Returns the all-time message counts of every bridge, per topic.
     *
     * @return Passário's response
     */
    public ResponseEntity<byte[]> traffic() {
        return forward(HttpMethod.GET, BRIDGES + "/traffic", null, null);
    }

    /**
     * Returns the all-time message counts of one bridge, per topic.
     *
     * @param id bridge id
     * @return Passário's response
     */
    public ResponseEntity<byte[]> traffic(String id) {
        return forward(HttpMethod.GET, BRIDGES + "/" + id + "/traffic", null, null);
    }

    /**
     * Returns the per-minute message counts of one bridge.
     *
     * @param id bridge id
     * @param from first minute, or {@code null} for Passário's default
     * @param to end of the range, or {@code null} for now
     * @return Passário's response
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
     * @return Passário's response
     */
    public ResponseEntity<byte[]> get(String id) {
        return forward(HttpMethod.GET, BRIDGES + "/" + id, null, null);
    }

    /**
     * Creates a bridge.
     *
     * @param body bridge definition as JSON
     * @param user client IP address, recorded by Passário
     * @return Passário's response
     */
    public ResponseEntity<byte[]> create(String body, String user) {
        return forward(HttpMethod.POST, BRIDGES, body, user);
    }

    /**
     * Replaces a bridge definition.
     *
     * @param id bridge id
     * @param body bridge definition as JSON
     * @param user client IP address, recorded by Passário
     * @return Passário's response
     */
    public ResponseEntity<byte[]> update(String id, String body, String user) {
        return forward(HttpMethod.PUT, BRIDGES + "/" + id, body, user);
    }

    /**
     * Deletes a bridge.
     *
     * @param id bridge id
     * @param user client IP address, recorded by Passário
     * @return Passário's response
     */
    public ResponseEntity<byte[]> delete(String id, String user) {
        return forward(HttpMethod.DELETE, BRIDGES + "/" + id, null, user);
    }

    /**
     * Enables or disables a bridge.
     *
     * @param id bridge id
     * @param enabled desired state
     * @param user client IP address, recorded by Passário
     * @return Passário's response
     */
    public ResponseEntity<byte[]> setEnabled(String id, boolean enabled, String user) {
        return forward(HttpMethod.POST, BRIDGES + "/" + id + (enabled ? "/enable" : "/disable"), null, user);
    }

    private ResponseEntity<byte[]> forward(HttpMethod method, String path, String body, String user) {
        try {
            RestClient.RequestBodySpec request = passario.method(method).uri(endpoint.url() + path)
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
            throw new PassarioUnavailableException(e.getCause() instanceof IOException ? e.getCause() : e);
        }
    }
}
