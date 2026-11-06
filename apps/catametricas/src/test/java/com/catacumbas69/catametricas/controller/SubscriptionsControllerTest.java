package com.catacumbas69.catametricas.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catametricas.dto.SubscriptionSeriesResponse;
import com.catacumbas69.catametricas.dto.SubscriptionStatsResponse;
import com.catacumbas69.catametricas.service.SubscriptionQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SubscriptionsController.class)
class SubscriptionsControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:05:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SubscriptionQueryService subscriptions;

    @MockitoBean
    private Clock clock;

    @Test
    void list_returnsEachSubscription() throws Exception {
        when(subscriptions.subscriptions("home", "media", "orders")).thenReturn(List.of(new SubscriptionStatsResponse(
                "persistent://home/media/orders", "home", "media", "orders", "orders", "audit", "Shared", NOW, 7, 1,
                2, 0.5)));

        mvc.perform(get("/api/v1/subscriptions").param("tenant", "home").param("namespace", "media")
                        .param("topic", "orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subscription").value("audit"))
                .andExpect(jsonPath("$[0].backlog").value(7));
    }

    @Test
    void series_buildsTheRange_withNowAsDefaultEnd() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        when(subscriptions.series(any(), eq("orders"), eq("audit"))).thenReturn(new SubscriptionSeriesResponse(
                "mongodb", "home", "media", "orders", "audit", 300, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));

        mvc.perform(get("/api/v1/subscriptions/series").param("tenant", "home").param("namespace", "media")
                        .param("topic", "orders").param("subscription", "audit")
                        .param("from", "2026-11-06T08:05:00Z").param("step", "5m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepSeconds").value(300));

        verify(subscriptions).series(argThat(query -> query.times().size() == 13), eq("orders"), eq("audit"));
    }

    @Test
    void series_refusesANameWithASlash() throws Exception {
        mvc.perform(get("/api/v1/subscriptions/series").param("tenant", "home").param("namespace", "media")
                        .param("topic", "a/b").param("subscription", "audit")
                        .param("from", "2026-11-06T08:05:00Z").param("step", "5m"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(subscriptions);
    }
}
