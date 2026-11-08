package com.catacumbas69.catavigia.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catavigia.model.BacklogLimit;
import com.catacumbas69.catavigia.service.BacklogLimitService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LimitController.class)
class LimitControllerTest {

    private static final String TOPIC = "persistent://home/media/orders";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BacklogLimitService limits;

    @Test
    void list_givesTheGlobalDefaultAndEachOwnLimit() throws Exception {
        when(limits.list()).thenReturn(List.of(limit(500)));

        mvc.perform(get("/api/v1/limits/backlog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.globalLimit").value(10000))
                .andExpect(jsonPath("$.limits[0].limit").value(500));
    }

    @Test
    void set_storesTheLimit_withTheBrowserAddress() throws Exception {
        when(limits.set(TOPIC, "audit", 500, "10.0.0.5")).thenReturn(limit(500));

        mvc.perform(put("/api/v1/limits/backlog").param("topic", TOPIC).param("subscription", "audit")
                        .header("X-Forwarded-For", "10.0.0.5")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(500));
    }

    @Test
    void set_refusesALimitOutOfRange_andATopicWithoutItsDomain() throws Exception {
        mvc.perform(put("/api/v1/limits/backlog").param("topic", TOPIC).param("subscription", "audit")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/limits/backlog").param("topic", "home/media/orders").param("subscription", "a")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(limits);
    }

    @Test
    void remove_goesBackToTheGlobalDefault_or404() throws Exception {
        when(limits.remove(TOPIC, "audit", "127.0.0.1")).thenReturn(true);
        mvc.perform(delete("/api/v1/limits/backlog").param("topic", TOPIC).param("subscription", "audit"))
                .andExpect(status().isNoContent());
        verify(limits).remove(TOPIC, "audit", "127.0.0.1");

        when(limits.remove(anyString(), anyString(), anyString())).thenReturn(false);
        mvc.perform(delete("/api/v1/limits/backlog").param("topic", TOPIC).param("subscription", "other"))
                .andExpect(status().isNotFound());
    }

    private static BacklogLimit limit(long value) {
        return new BacklogLimit(BacklogLimit.idOf(TOPIC, "audit"), TOPIC, "audit", value, "10.0.0.5",
                Instant.parse("2026-11-07T09:00:00Z"));
    }
}
