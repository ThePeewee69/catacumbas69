package com.catacumbas69.catametricas.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catametricas.dto.CollectorResponse;
import com.catacumbas69.catametricas.dto.SeriesResponse;
import com.catacumbas69.catametricas.dto.TopicStatsResponse;
import com.catacumbas69.catametricas.service.MetricsQueryService;
import com.catacumbas69.catametricas.service.SeriesQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MetricsController.class)
class MetricsControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-01T09:05:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MetricsQueryService metrics;

    @MockitoBean
    private Clock clock;

    @Test
    void topics_returnsCurrentStateAndTotals() throws Exception {
        when(metrics.topics("home", "media")).thenReturn(List.of(new TopicStatsResponse(
                "persistent://home/media/orders", "home", "media", "orders", "orders",
                new TopicStatsResponse.Current(NOW, 3, 30, 1, 0, 10, 0, 100, 1, 1, 0),
                new TopicStatsResponse.Traffic(500, 400, 5000, 4000))));

        mvc.perform(get("/api/v1/topics").param("tenant", "home").param("namespace", "media"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].baseName").value("orders"))
                .andExpect(jsonPath("$[0].current.backlogMsgs").value(3))
                .andExpect(jsonPath("$[0].total.msgsIn").value(500));
    }

    @Test
    void series_buildsTheQuery_withNowAsDefaultEnd() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        when(metrics.series(any())).thenReturn(new SeriesResponse("mongodb", "topic", 900, List.of(), List.of()));

        mvc.perform(get("/api/v1/series").param("tenant", "home").param("namespace", "media")
                        .param("groupBy", "topic").param("from", "2026-11-01T08:00:00Z").param("step", "15m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("mongodb"));

        verify(metrics).series(argThat((SeriesQuery query) -> query.groupBy() == SeriesQuery.GroupBy.TOPIC
                && query.times().size() == 5 && "media".equals(query.namespace())));
    }

    @Test
    void series_rejectsAQueryThatDoesNotFit() throws Exception {
        mvc.perform(get("/api/v1/series").param("groupBy", "topic").param("from", "2026-11-01T08:00:00Z")
                        .param("to", "2026-11-01T09:00:00Z").param("step", "15m"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid query"));

        verifyNoInteractions(metrics);
    }

    @Test
    void rejectsInvalidNames() throws Exception {
        mvc.perform(get("/api/v1/topics").param("tenant", "bad name"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void collector_returnsTheLatestCollection() throws Exception {
        when(metrics.collector()).thenReturn(new CollectorResponse(NOW, 60));

        mvc.perform(get("/api/v1/collector"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalSeconds").value(60));
    }

    @Test
    void answers503_whenMongoDbIsDown() throws Exception {
        when(metrics.collector()).thenThrow(new DataAccessResourceFailureException("down"));

        mvc.perform(get("/api/v1/collector"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Database unavailable"));
    }
}
