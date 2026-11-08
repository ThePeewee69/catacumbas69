package com.catacumbas69.catavigia.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catavigia.dto.AlertResponse;
import com.catacumbas69.catavigia.dto.AlertSummaryResponse;
import com.catacumbas69.catavigia.service.AlertQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(AlertController.class)
class AlertControllerTest {

    private static final String ID = "0b6f5c2e-8a51-4c43-9c1e-2f3a4b5c6d7e";
    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AlertQueryService alerts;

    @Test
    void open_listsTheAlerts() throws Exception {
        when(alerts.open()).thenReturn(List.of(alert()));

        mvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("backlog"))
                .andExpect(jsonPath("$[0].details.limit").value("10000"));
    }

    @Test
    void summary_givesTheWorstSeverity() throws Exception {
        when(alerts.summary()).thenReturn(new AlertSummaryResponse(3, 2, 1, 1, "critical", true));

        mvc.perform(get("/api/v1/alerts/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worst").value("critical"));
    }

    @Test
    void seen_isRecordedWithTheBrowserAddress() throws Exception {
        when(alerts.markSeen(eq(ID), eq("10.0.0.5"))).thenReturn(alert());

        mvc.perform(post("/api/v1/alerts/" + ID + "/seen").header("X-Forwarded-For", "10.0.0.5, 10.1.1.1"))
                .andExpect(status().isOk());

        verify(alerts).markSeen(ID, "10.0.0.5");
    }

    @Test
    void seen_ofAnUnknownAlert_is404() throws Exception {
        when(alerts.markSeen(eq(ID), eq("127.0.0.1")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No open alert"));

        mvc.perform(post("/api/v1/alerts/" + ID + "/seen")).andExpect(status().isNotFound());
    }

    @Test
    void refusesAnIdThatIsNotOne() throws Exception {
        mvc.perform(get("/api/v1/alerts/not-an-id")).andExpect(status().isBadRequest());

        verifyNoInteractions(alerts);
    }

    private static AlertResponse alert() {
        return new AlertResponse(ID, "backlog|persistent://home/media/orders|audit", "backlog",
                "persistent://home/media/orders|audit", "warning", Map.of("limit", "10000"), NOW, NOW, null, null,
                null, null, false);
    }
}
