package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
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

    private final CataponteEndpoint endpoint = mock(CataponteEndpoint.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer cataponte = MockRestServiceServer.bindTo(builder).build();
    private final EventPublisher events = mock(EventPublisher.class);
    private final BridgeProxyService service = new BridgeProxyService(builder.build(), endpoint, events,
            new ObjectMapper());

    @BeforeEach
    void cataponteUrl() {
        when(endpoint.url()).thenReturn("http://cataponte");
    }

    @Test
    void create_forwardsBody_andClientAddress() {
        cataponte.expect(requestTo("http://cataponte/api/v1/bridges"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(BridgeProxyService.FORWARDED_FOR, "10.0.0.1"))
                .andExpect(content().json("{\"name\":\"fanout\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(BRIDGE_JSON));

        ResponseEntity<byte[]> response = service.create("{\"name\":\"fanout\"}", "10.0.0.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(BRIDGE_JSON);
        cataponte.verify();
        verify(events).publish("bridge", "create-requested", "b1",
                EventData.action("10.0.0.1", Map.of("status", 201, "request", "{\"name\":\"fanout\"}")));
    }

    @Test
    void passesThroughProblemDetails() {
        String problem = "{\"title\":\"Conflict\",\"status\":409,\"detail\":\"name taken\"}";
        cataponte.expect(requestTo("http://cataponte/api/v1/bridges/b1"))
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
        cataponte.expect(requestTo("http://cataponte/api/v1/bridges/b1/enable"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(BridgeProxyService.FORWARDED_FOR, "10.0.0.1"))
                .andRespond(withSuccess(BRIDGE_JSON, MediaType.APPLICATION_JSON));

        service.setEnabled("b1", true, "10.0.0.1");

        cataponte.verify();
    }

    @Test
    void list_sendsNoClientAddress() {
        cataponte.expect(requestTo("http://cataponte/api/v1/bridges"))
                .andExpect(headerDoesNotExist(BridgeProxyService.FORWARDED_FOR))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        service.list();

        cataponte.verify();
    }

    @Test
    void trafficMinutes_passesTheRange() {
        cataponte.expect(requestTo("http://cataponte/api/v1/bridges/b1/traffic/minutes"
                        + "?from=2026-10-30T09:00:00Z&to=2026-10-30T10:00:00Z"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        service.trafficMinutes("b1", Instant.parse("2026-10-30T09:00:00Z"), Instant.parse("2026-10-30T10:00:00Z"));

        cataponte.verify();
    }

    @Test
    void usesTheUrlInUse_atCallTime() {
        when(endpoint.url()).thenReturn("http://other:9000");
        cataponte.expect(requestTo("http://other:9000/api/v1/bridges/traffic"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        service.traffic();

        cataponte.verify();
    }

    @Test
    void throwsUnavailable_whenCataponteCannotBeReached() {
        RestClient unreachable = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new IOException("Connection refused");
                })
                .build();
        BridgeProxyService proxy = new BridgeProxyService(unreachable, endpoint, events, new ObjectMapper());

        assertThatThrownBy(proxy::list)
                .isInstanceOf(CataponteUnavailableException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    void publishesRequestEvent_whenCataponteCannotBeReached() {
        RestClient unreachable = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new IOException("Connection refused");
                })
                .build();
        BridgeProxyService proxy = new BridgeProxyService(unreachable, endpoint, events, new ObjectMapper());

        assertThatThrownBy(() -> proxy.delete("b1", "10.0.0.1")).isInstanceOf(CataponteUnavailableException.class);

        verify(events).publish(eq("bridge"), eq("delete-requested"), eq("b1"),
                eq(EventData.action("10.0.0.1", Map.of("status", 503))));
    }
}
