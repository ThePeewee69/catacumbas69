package com.catacumbas69.catapulsar.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catapulsar.dto.OverviewResponse;
import com.catacumbas69.catapulsar.service.CatametricasClient;
import com.catacumbas69.catapulsar.service.CatametricasUnavailableException;
import com.catacumbas69.catapulsar.service.OverviewPeriod;
import com.catacumbas69.catapulsar.service.OverviewService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OverviewController.class)
class OverviewControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OverviewService overview;

    @Test
    void tenant_usesTheRequestedPeriod() throws Exception {
        when(overview.tenant("home", OverviewPeriod.LAST_WEEK)).thenReturn(empty("tenant"));

        mvc.perform(get("/api/v1/overview/home").param("period", "7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("tenant"));
    }

    @Test
    void cluster_defaultsToTheLastDay() throws Exception {
        when(overview.cluster(OverviewPeriod.LAST_DAY)).thenReturn(empty("cluster"));

        mvc.perform(get("/api/v1/overview")).andExpect(status().isOk());
    }

    @Test
    void rejectsUnknownPeriods() throws Exception {
        mvc.perform(get("/api/v1/overview/home/media").param("period", "2h"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("period")));
        verify(overview, never()).namespace(any(), any(), any());
    }

    @Test
    void returns503_whenCatametricasIsDown() throws Exception {
        when(overview.cluster(any())).thenThrow(new CatametricasUnavailableException(new IOException("refused")));

        mvc.perform(get("/api/v1/overview"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Catametricas unavailable"));
    }

    @Test
    void collect_forwardsTheScopeAndTheBrowserAddress() throws Exception {
        when(overview.collect("home", "media", "10.0.0.1")).thenReturn(new CatametricasClient.ForcedCollection(
                "home/media", List.of("home/media"), 1, 0, List.of(), Instant.parse("2026-11-04T09:00:07Z"), 180));

        mvc.perform(post("/api/v1/overview/collect").param("tenant", "home").param("namespace", "media")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collected").value(1));
    }

    @Test
    void collect_rejectsInvalidNames() throws Exception {
        mvc.perform(post("/api/v1/overview/collect").param("tenant", "bad name"))
                .andExpect(status().isBadRequest());
        verify(overview, never()).collect(any(), any(), any());
    }

    @Test
    void collect_returns503_whenCatametricasIsDown() throws Exception {
        when(overview.collect(null, null, "127.0.0.1"))
                .thenThrow(new CatametricasUnavailableException(new IOException("refused")));

        mvc.perform(post("/api/v1/overview/collect")).andExpect(status().isServiceUnavailable());
    }

    private static OverviewResponse empty(String scope) {
        OverviewResponse.Row none = new OverviewResponse.Row(null, null, OverviewResponse.Traffic.NONE,
                OverviewResponse.Traffic.NONE, new OverviewResponse.Series(List.of(), List.of(), List.of()));
        return new OverviewResponse(scope, null, null, "24h", 900, List.of(), null, null, none, List.of());
    }
}
