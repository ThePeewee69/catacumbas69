package com.catacumbas69.catapulsar.service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Backend-for-frontend for the audit view (ADR 0009): forwards read-only calls from the browser to Cataudit,
 * which owns the audit trail, at the URL held by {@link CatauditEndpoint}. Bodies and statuses are passed
 * through unchanged, including Cataudit's Problem Details. If Cataudit cannot be reached a
 * {@link CatauditUnavailableException} is thrown, which the API maps to {@code 503} (RES-12).
 */
@Service
public class AuditProxyService {

    private static final String AUDIT = "/api/v1/audit";

    private final RestClient cataudit;
    private final CatauditEndpoint endpoint;

    /**
     * Creates the service.
     *
     * @param cataudit HTTP client for Cataudit (timeouts only; the URL comes from {@code endpoint})
     * @param endpoint Cataudit base URL, editable in the UI
     */
    public AuditProxyService(@Qualifier("catauditRestClient") RestClient cataudit, CatauditEndpoint endpoint) {
        this.cataudit = cataudit;
        this.endpoint = endpoint;
    }

    /**
     * Searches the audit trail.
     *
     * @param filters query parameters, passed through (app, entity, action, subject, actor, from, to, page,
     *                size)
     * @return Cataudit's response
     */
    public ResponseEntity<byte[]> search(MultiValueMap<String, String> filters) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(endpoint.url()).path(AUDIT);
        filters.forEach((name, values) -> values.forEach(value ->
                uri.queryParam(UriUtils.encode(name, StandardCharsets.UTF_8),
                        UriUtils.encode(value, StandardCharsets.UTF_8))));
        return forward(uri.build(true).toUri());
    }

    /**
     * Returns the apps, entities and actions in use.
     *
     * @return Cataudit's response
     */
    public ResponseEntity<byte[]> facets() {
        return forward(URI.create(endpoint.url() + AUDIT + "/facets"));
    }

    /**
     * Returns one entry.
     *
     * @param id event id
     * @return Cataudit's response
     */
    public ResponseEntity<byte[]> get(String id) {
        return forward(URI.create(endpoint.url() + AUDIT + "/"
                + UriUtils.encodePathSegment(id, StandardCharsets.UTF_8)));
    }

    private ResponseEntity<byte[]> forward(URI uri) {
        try {
            return cataudit.get().uri(uri)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .exchange((request, response) -> {
                        HttpHeaders headers = new HttpHeaders();
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType != null) {
                            headers.setContentType(contentType);
                        }
                        byte[] body = response.getBody().readAllBytes();
                        return ResponseEntity.status(response.getStatusCode()).headers(headers).body(body);
                    });
        } catch (ResourceAccessException e) {
            throw new CatauditUnavailableException(e.getCause() instanceof IOException ? e.getCause() : e);
        }
    }
}
