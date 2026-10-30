package com.catacumbas69.pulsario.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BridgeProxyServiceTest {

    private static final String BRIDGE_JSON = "{\"id\":\"b1\",\"name\":\"fanout\"}";

    private final AuditService audit = mock(AuditService.class);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://passario");
    private final MockRestServiceServer passario = MockRestServiceServer.bindTo(builder).build();
    private final BridgeProxyService service = new BridgeProxyService(builder.build(), audit);

    @Test
    void create_forwardsBody_andAuditsSuccess() {
        passario.expect(requestTo("http://passario/api/v1/bridges"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"name\":\"fanout\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(BRIDGE_JSON));

        ResponseEntity<byte[]> response = service.create("{\"name\":\"fanout\"}", "10.0.0.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(BRIDGE_JSON);
        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.CREATE_BRIDGE), isNull(), isNull());
        passario.verify();
    }

    @Test
    void passesThroughProblemDetails_withoutAuditing() {
        String problem = "{\"title\":\"Conflict\",\"status\":409,\"detail\":\"name taken\"}";
        passario.expect(requestTo("http://passario/api/v1/bridges/b1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(problem));

        ResponseEntity<byte[]> response = service.update("b1", "{}", "10.0.0.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(problem);
        verifyNoInteractions(audit);
    }

    @Test
    void enable_callsEnableEndpoint_andAuditsWithBridgeId() {
        passario.expect(requestTo("http://passario/api/v1/bridges/b1/enable"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(BRIDGE_JSON, MediaType.APPLICATION_JSON));

        service.setEnabled("b1", true, "10.0.0.1");

        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.ENABLE_BRIDGE), isNull(), eq(Map.of("bridgeId", "b1")));
    }

    @Test
    void list_doesNotAudit() {
        passario.expect(requestTo("http://passario/api/v1/bridges"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        service.list();

        verifyNoInteractions(audit);
    }

    @Test
    void throwsUnavailable_whenPassarioCannotBeReached() {
        RestClient unreachable = RestClient.builder()
                .baseUrl("http://passario")
                .requestFactory((uri, httpMethod) -> {
                    throw new IOException("Connection refused");
                })
                .build();
        BridgeProxyService proxy = new BridgeProxyService(unreachable, audit);

        assertThatThrownBy(proxy::list)
                .isInstanceOf(PassarioUnavailableException.class)
                .hasMessageContaining("Connection refused");
        verify(audit, org.mockito.Mockito.never()).record(any(), any(), any(), any());
    }
}
