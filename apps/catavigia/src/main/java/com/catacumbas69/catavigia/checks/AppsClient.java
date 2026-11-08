package com.catacumbas69.catavigia.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reads JSON from the apps, with the checks' timeout.
 */
@Component
public class AppsClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    /**
     * Creates the client.
     *
     * @param appsRestClient HTTP client from {@code CoreConfig}
     * @param objectMapper reads error bodies
     */
    public AppsClient(RestClient appsRestClient, ObjectMapper objectMapper) {
        this.client = appsRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads a JSON document.
     *
     * @param url full URL
     * @return the document
     * @throws SourceUnavailableException if the call fails or does not answer 2xx
     */
    public JsonNode get(String url) throws SourceUnavailableException {
        try {
            JsonNode body = client.get().uri(URI.create(url)).retrieve().body(JsonNode.class);
            if (body == null) {
                throw new SourceUnavailableException(url + " answered with no body", null);
            }
            return body;
        } catch (RestClientException | IllegalArgumentException e) {
            throw new SourceUnavailableException(url + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads a JSON document that may come with an error status, as Spring Boot's health does ({@code 503} with
     * {@code {"status":"DOWN"}}).
     *
     * @param url full URL
     * @return the document, whatever the status
     * @throws SourceUnavailableException if nothing usable came back (no answer, or no JSON)
     */
    public JsonNode getAnyStatus(String url) throws SourceUnavailableException {
        try {
            return get(url);
        } catch (SourceUnavailableException e) {
            if (e.getCause() instanceof RestClientResponseException response) {
                try {
                    return objectMapper.readTree(response.getResponseBodyAsString());
                } catch (IOException | RuntimeException ignored) {
                    // not JSON: reported as unavailable below
                }
                throw new SourceUnavailableException(url + " answered " + response.getStatusCode().value(), e);
            }
            throw e;
        }
    }
}
