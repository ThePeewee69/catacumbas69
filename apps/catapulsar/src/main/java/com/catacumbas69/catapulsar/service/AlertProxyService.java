package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Backend-for-frontend of the alerts (ADR 0015): forwards {@code /api/v1/alerts/**} and {@code /api/v1/limits/**}
 * to Catavigia, with the browser's address as {@code X-Forwarded-For} so Catavigia records who did it. Marking an
 * alert as seen and changing a backlog limit are also published by Catapulsar as {@code alert.seen-requested} and
 * {@code limit.change-requested}, with Catavigia's HTTP status, as for the bridges.
 */
@Service
public class AlertProxyService {

    private static final Logger LOG = LoggerFactory.getLogger(AlertProxyService.class);
    private static final String ALERTS = "/api/v1/alerts";
    private static final String LIMITS = "/api/v1/limits/backlog";
    private static final int UNAVAILABLE = 503;

    private final RestClient catavigia;
    private final CatavigiaEndpoint endpoint;
    private final EventPublisher events;

    /**
     * Creates the proxy.
     *
     * @param catavigia HTTP client for Catavigia
     * @param endpoint Catavigia base URL in use
     * @param events publishes the request events
     */
    public AlertProxyService(@Qualifier("catavigiaRestClient") RestClient catavigia, CatavigiaEndpoint endpoint,
                             EventPublisher events) {
        this.catavigia = catavigia;
        this.endpoint = endpoint;
        this.events = events;
    }

    /**
     * Forwards a read.
     *
     * @param path path under the API, e.g. {@code /api/v1/alerts/summary}
     * @param query query parameters to pass on
     * @return Catavigia's response
     */
    public ResponseEntity<byte[]> get(String path, MultiValueMap<String, String> query) {
        return forward(HttpMethod.GET, uri(path, query), null, null);
    }

    /**
     * Marks an alert as seen.
     *
     * @param id alert id
     * @param user client address
     * @return Catavigia's response
     */
    public ResponseEntity<byte[]> seen(String id, String user) {
        return requested(AuditAction.SEE_ALERT, id, null, user,
                () -> forward(HttpMethod.POST, uri(ALERTS + "/" + id + "/seen", null), null, user));
    }

    /**
     * Sets a subscription's own backlog limit.
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @param body {@code {"limit": n}}
     * @param user client address
     * @return Catavigia's response
     */
    public ResponseEntity<byte[]> setLimit(String topic, String subscription, String body, String user) {
        return requested(AuditAction.CHANGE_LIMIT, topic + "|" + subscription, body, user,
                () -> forward(HttpMethod.PUT, limit(topic, subscription), body, user));
    }

    /**
     * Removes a subscription's own backlog limit.
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @param user client address
     * @return Catavigia's response
     */
    public ResponseEntity<byte[]> removeLimit(String topic, String subscription, String user) {
        return requested(AuditAction.CHANGE_LIMIT, topic + "|" + subscription, "{\"limit\":\"global\"}", user,
                () -> forward(HttpMethod.DELETE, limit(topic, subscription), null, user));
    }

    private URI limit(String topic, String subscription) {
        return UriComponentsBuilder.fromUriString(endpoint.url()).path(LIMITS)
                .queryParam("topic", topic).queryParam("subscription", subscription).encode().build().toUri();
    }

    private URI uri(String path, MultiValueMap<String, String> query) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(endpoint.url()).path(path);
        if (query != null) {
            uri.queryParams(query);
        }
        return uri.encode().build().toUri();
    }

    private ResponseEntity<byte[]> requested(AuditAction action, String subject, String request, String user,
                                             Supplier<ResponseEntity<byte[]>> call) {
        ResponseEntity<byte[]> response;
        try {
            response = call.get();
        } catch (CatavigiaUnavailableException e) {
            publishRequested(action, subject, request, user, UNAVAILABLE);
            throw e;
        }
        publishRequested(action, subject, request, user, response.getStatusCode().value());
        return response;
    }

    private void publishRequested(AuditAction action, String subject, String body, String user, int status) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", status);
            if (body != null) {
                details.put("request", body);
            }
            events.publish(action.entity(), action.verb(), subject, EventData.action(user, details));
        } catch (RuntimeException e) {
            LOG.warn("Event not published: {} {}: {}", action, subject, e.getMessage());
        }
    }

    private ResponseEntity<byte[]> forward(HttpMethod method, URI uri, String body, String user) {
        try {
            RestClient.RequestBodySpec request = catavigia.method(method).uri(uri)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
            if (user != null) {
                request.header(BridgeProxyService.FORWARDED_FOR, user);
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
            throw new CatavigiaUnavailableException(e.getCause() instanceof IOException ? e.getCause() : e);
        }
    }
}
