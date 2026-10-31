package com.catacumbas69.pulsario.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BridgeProxyServiceTest {

    private static final String BRIDGE_JSON = "{\"id\":\"b1\",\"name\":\"fanout\"}";

    private final PassarioEndpoint endpoint = mock(PassarioEndpoint.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer passario = MockRestServiceServer.bindTo(builder).build();
    private final BridgeProxyService service = new BridgeProxyService(builder.build(), endpoint);

    @BeforeEach
    void passarioUrl() {
        when(endpoint.url()).thenReturn("http://passario");
    }

    @Test
    void create_forwardsBody_andClientAddress() {
        passario.expect(requestTo("http://passario/api/v1/bridges"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(BridgeProxyService.FORWARDED_FOR, "10.0.0.1"))
                .andExpect(content().json("{\"name\":\"fanout\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(BRIDGE_JSON));

        ResponseEntity<byte[]> response = service.create("{\"name\":\"fanout\"}", "10.0.0.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(BRIDGE_JSON);
        passario.verify();
    }

    @Test
    void passesThroughProblemDetails() {
        String problem = "{\"title\":\"Conflict\",\"status\":409,\"detail\":\"name taken\"}";
        passario.expect(requestTo("http://passario/api/v1/bridges/b1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(problem));

        ResponseEntity<byte[]> response = service.update("b1", "{}", "10.0.0.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(problem);
    }

    @Test
    void enable_callsEnableEndpoint() {
        passario.expect(requestTo("http://passario/api/v1/bridges/b1/enable"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(BridgeProxyService.FORWARDED_FOR, "10.0.0.1"))
                .andRespond(withSuccess(BRIDGE_JSON, MediaType.APPLICATION_JSON));

        service.setEnabled("b1", true, "10.0.0.1");

        passario.verify();
    }

    @Test
    void list_sendsNoClientAddress() {
        passario.expect(requestTo("http://passario/api/v1/bridges"))
                .andExpect(headerDoesNotExist(BridgeProxyService.FORWARDED_FOR))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        service.list();

        passario.verify();
    }

    @Test
    void trafficMinutes_passesTheRange() {
        passario.expect(requestTo("http://passario/api/v1/bridges/b1/traffic/minutes"
                        + "?from=2026-10-30T09:00:00Z&to=2026-10-30T10:00:00Z"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        service.trafficMinutes("b1", Instant.parse("2026-10-30T09:00:00Z"), Instant.parse("2026-10-30T10:00:00Z"));

        passario.verify();
    }

    @Test
    void usesTheUrlInUse_atCallTime() {
        when(endpoint.url()).thenReturn("http://other:9000");
        passario.expect(requestTo("http://other:9000/api/v1/bridges/traffic"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        service.traffic();

        passario.verify();
    }

    @Test
    void throwsUnavailable_whenPassarioCannotBeReached() {
        RestClient unreachable = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new IOException("Connection refused");
                })
                .build();
        BridgeProxyService proxy = new BridgeProxyService(unreachable, endpoint);

        assertThatThrownBy(proxy::list)
                .isInstanceOf(PassarioUnavailableException.class)
                .hasMessageContaining("Connection refused");
    }
}
