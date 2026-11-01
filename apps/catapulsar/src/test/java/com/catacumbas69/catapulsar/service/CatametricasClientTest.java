package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CatametricasClientTest {

    private final CatametricasEndpoint endpoint = mock(CatametricasEndpoint.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer catametricas = MockRestServiceServer.bindTo(builder).build();
    private final CatametricasClient client = new CatametricasClient(builder.build(), endpoint);

    CatametricasClientTest() {
        when(endpoint.url()).thenReturn("http://localhost:8083");
    }

    @Test
    void topics_passesTheScope() {
        catametricas.expect(requestTo(URI.create("http://localhost:8083/api/v1/topics?tenant=home&namespace=media")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"topic\":\"persistent://home/media/orders\",\"baseName\":\"orders\","
                        + "\"current\":{\"collectedAt\":\"2026-11-01T10:00:00Z\",\"backlogMsgs\":3},"
                        + "\"total\":{\"msgsIn\":500}}]", MediaType.APPLICATION_JSON));

        assertThat(client.topics("home", "media")).singleElement().satisfies(topic -> {
            assertThat(topic.current().backlogMsgs()).isEqualTo(3);
            assertThat(topic.current().collectedAt()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
            assertThat(topic.total().msgsIn()).isEqualTo(500);
        });
    }

    @Test
    void series_passesRangeGroupAndStep() {
        catametricas.expect(requestTo(URI.create("http://localhost:8083/api/v1/series?groupBy=tenant"
                        + "&from=2026-11-01T09:00:00Z&to=2026-11-01T10:00:00Z&step=15m")))
                .andRespond(withSuccess("{\"source\":\"mongodb\",\"stepSeconds\":900,"
                        + "\"times\":[\"2026-11-01T09:00:00Z\"],\"groups\":[{\"key\":\"home\",\"msgsIn\":[4],"
                        + "\"msgsOut\":[0],\"bytesIn\":[0],\"bytesOut\":[0],\"backlog\":[null]}]}",
                        MediaType.APPLICATION_JSON));

        CatametricasClient.Series series = client.series(null, null, "tenant",
                Instant.parse("2026-11-01T09:00:00Z"), Instant.parse("2026-11-01T10:00:00Z"), "15m");

        assertThat(series.groups()).singleElement().satisfies(group -> {
            assertThat(group.msgsIn()).containsExactly(4L);
            assertThat(group.backlog()).containsExactly((Long) null);
        });
    }

    @Test
    void failsAsUnavailable_whenCatametricasAnswers503() {
        catametricas.expect(requestTo(URI.create("http://localhost:8083/api/v1/collector")))
                .andRespond(withServiceUnavailable());

        assertThatThrownBy(client::collector).isInstanceOf(CatametricasUnavailableException.class);
    }

    @Test
    void failsAsUnavailable_whenCatametricasCannotBeReached() {
        when(endpoint.url()).thenReturn("http://127.0.0.1:1");
        CatametricasClient direct = new CatametricasClient(RestClient.create(), endpoint);

        assertThatThrownBy(direct::collector).isInstanceOf(CatametricasUnavailableException.class);
    }
}
