package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

class AuditProxyServiceTest {

    private final CatauditEndpoint endpoint = mock(CatauditEndpoint.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer cataudit = MockRestServiceServer.bindTo(builder).build();
    private final AuditProxyService service = new AuditProxyService(builder.build(), endpoint);

    AuditProxyServiceTest() {
        when(endpoint.url()).thenReturn("http://localhost:8084");
    }

    @Test
    void search_forwardsFilters_encoded() {
        cataudit.expect(requestTo(URI.create("http://localhost:8084/api/v1/audit?app=catapulsar"
                        + "&subject=persistent%3A%2F%2Fa%2Fb%2B&from=2026-10-31T00%3A00%3A00Z")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[],\"total\":0}", MediaType.APPLICATION_JSON));
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("app", "catapulsar");
        filters.add("subject", "persistent://a/b+");
        filters.add("from", "2026-10-31T00:00:00Z");

        ResponseEntity<byte[]> response = service.search(filters);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("\"total\":0");
        cataudit.verify();
    }

    @Test
    void get_passesThroughNotFound() {
        cataudit.expect(requestTo("http://localhost:8084/api/v1/audit/legacy-1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":404}"));

        assertThat(service.get("legacy-1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void throwsUnavailable_whenCatauditCannotBeReached() {
        RestClient unreachable = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new IOException("Connection refused");
                })
                .build();
        AuditProxyService proxy = new AuditProxyService(unreachable, endpoint);

        assertThatThrownBy(proxy::facets)
                .isInstanceOf(CatauditUnavailableException.class)
                .hasMessageContaining("Connection refused");
    }
}
