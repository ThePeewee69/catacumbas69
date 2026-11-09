package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

class AlertProxyServiceTest {

    private static final String ID = "0b6f5c2e-8a51-4c43-9c1e-2f3a4b5c6d7e";

    private final CatavigiaEndpoint endpoint = mock(CatavigiaEndpoint.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer catavigia = MockRestServiceServer.bindTo(builder).build();
    private final EventPublisher events = mock(EventPublisher.class);
    private final AlertProxyService service = new AlertProxyService(builder.build(), endpoint, events);

    @BeforeEach
    void catavigiaUrl() {
        when(endpoint.url()).thenReturn("http://catavigia");
    }

    @Test
    void archive_passesTheFiltersOn() {
        catavigia.expect(requestTo("http://catavigia/api/v1/alerts/archive?type=backlog&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("type", "backlog");
        query.add("page", "1");

        ResponseEntity<byte[]> response = service.get("/api/v1/alerts/archive", query);

        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"items\":[]}");
        catavigia.verify();
    }

    @Test
    void seen_forwardsTheBrowserAddress_andPublishesTheRequest() {
        catavigia.expect(requestTo("http://catavigia/api/v1/alerts/" + ID + "/seen"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(BridgeProxyService.FORWARDED_FOR, "10.0.0.5"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(service.seen(ID, "10.0.0.5").getStatusCode()).isEqualTo(HttpStatus.OK);

        catavigia.verify();
        verify(events).publish("alert", "seen-requested", ID, EventData.action("10.0.0.5", Map.of("status", 200)));
    }

    @Test
    void setLimit_encodesTheTopic_andPublishesTheRequestWithCatavigiasStatus() {
        catavigia.expect(requestTo(
                        "http://catavigia/api/v1/limits/backlog?topic=persistent://home/media/orders&subscription=audit"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":400}"));

        ResponseEntity<byte[]> response = service.setLimit("persistent://home/media/orders", "audit",
                "{\"limit\":0}", "10.0.0.5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(events).publish("limit", "change-requested", "persistent://home/media/orders|audit",
                EventData.action("10.0.0.5", Map.of("status", 400, "request", "{\"limit\":0}")));
    }
}
