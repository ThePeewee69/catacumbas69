package com.catacumbas69.catasonarr.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QueueController.class)
class QueueControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void queue_asSeerrAsksForIt_isAnEmptyPage() throws Exception {
        mvc.perform(get("/api/v3/queue").param("includeEpisode", "true").param("pageSize", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(1000))
                .andExpect(jsonPath("$.sortKey").value("timeleft"))
                .andExpect(jsonPath("$.sortDirection").value("ascending"))
                .andExpect(jsonPath("$.totalRecords").value(0))
                .andExpect(jsonPath("$.records").isEmpty());
    }

    @Test
    void details_isAnEmptyList() throws Exception {
        mvc.perform(get("/api/v3/queue/details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void status_isAllZero() throws Exception {
        mvc.perform(get("/api/v3/queue/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.unknownCount").value(0))
                .andExpect(jsonPath("$.errors").value(false))
                .andExpect(jsonPath("$.warnings").value(false));
    }
}
