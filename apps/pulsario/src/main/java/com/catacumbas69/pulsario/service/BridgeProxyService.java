package com.catacumbas69.pulsario.service;

import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Backend-for-frontend for bridges (ARCH-2, ADR 0001): forwards bridge calls from the browser to Passário
 * and audits every successful change (OBS-6).
 *
 * <p>Passário owns validation and the bridge model, so request and response bodies are passed through
 * unchanged, including Passário's Problem Details on errors. If Passário cannot be reached a
 * {@link PassarioUnavailableException} is thrown, which the API maps to {@code 503} (RES-12).
 */
@Service
public class BridgeProxyService {

    private static final String BRIDGES = "/api/v1/bridges";

    private final RestClient passario;
    private final AuditService audit;

    /**
     * Creates the service.
     *
     * @param passario HTTP client for Passário
     * @param audit audit log
     */
    public BridgeProxyService(@Qualifier("passarioRestClient") RestClient passario, AuditService audit) {
        this.passario = passario;
        this.audit = audit;
    }

    /**
     * Lists bridges.
     *
     * @return Passário's response
     */
    public ResponseEntity<byte[]> list() {
        return forward(HttpMethod.GET, BRIDGES, null);
    }

    /**
     * Returns the runtime status of the bridges.
     *
     * @return Passário's response
     */
    public ResponseEntity<byte[]> status() {
        return forward(HttpMethod.GET, BRIDGES + "/status", null);
    }

    /**
     * Returns one bridge.
     *
     * @param id bridge id
     * @return Passário's response
     */
    public ResponseEntity<byte[]> get(String id) {
        return forward(HttpMethod.GET, BRIDGES + "/" + id, null);
    }

    /**
     * Creates a bridge and audits it.
     *
     * @param body bridge definition as JSON
     * @param user client IP address
     * @return Passário's response
     */
    public ResponseEntity<byte[]> create(String body, String user) {
        return audited(forward(HttpMethod.POST, BRIDGES, body), user, AuditAction.CREATE_BRIDGE, null);
    }

    /**
     * Replaces a bridge definition and audits it.
     *
     * @param id bridge id
     * @param body bridge definition as JSON
     * @param user client IP address
     * @return Passário's response
     */
    public ResponseEntity<byte[]> update(String id, String body, String user) {
        return audited(forward(HttpMethod.PUT, BRIDGES + "/" + id, body), user, AuditAction.UPDATE_BRIDGE, id);
    }

    /**
     * Deletes a bridge and audits it.
     *
     * @param id bridge id
     * @param user client IP address
     * @return Passário's response
     */
    public ResponseEntity<byte[]> delete(String id, String user) {
        return audited(forward(HttpMethod.DELETE, BRIDGES + "/" + id, null), user, AuditAction.DELETE_BRIDGE, id);
    }

    /**
     * Enables or disables a bridge and audits it.
     *
     * @param id bridge id
     * @param enabled desired state
     * @param user client IP address
     * @return Passário's response
     */
    public ResponseEntity<byte[]> setEnabled(String id, boolean enabled, String user) {
        String path = BRIDGES + "/" + id + (enabled ? "/enable" : "/disable");
        AuditAction action = enabled ? AuditAction.ENABLE_BRIDGE : AuditAction.DISABLE_BRIDGE;
        return audited(forward(HttpMethod.POST, path, null), user, action, id);
    }

    private ResponseEntity<byte[]> forward(HttpMethod method, String path, String body) {
        try {
            RestClient.RequestBodySpec request = passario.method(method).uri(path)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
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

    private ResponseEntity<byte[]> audited(ResponseEntity<byte[]> response, String user, AuditAction action,
                                           String bridgeId) {
        if (response.getStatusCode().is2xxSuccessful()) {
            audit.record(user, action, null, bridgeId == null ? null : Map.of("bridgeId", bridgeId));
        }
        return response;
    }
}
