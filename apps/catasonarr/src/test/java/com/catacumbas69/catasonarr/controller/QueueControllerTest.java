package com.catacumbas69.catasonarr.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catasonarr.download.QueueService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QueueController.class)
class QueueControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QueueService queue;

    private static QueueService.Item item(int id, String title, long secondsLeft) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("seriesId", 1);
        r.put("episodeId", id);
        r.put("title", title);
        r.put("size", 100L);
        r.put("sizeleft", 50L);
        r.put("estimatedCompletionTime", java.time.Instant.ofEpochSecond(secondsLeft));
        r.put("trackedDownloadStatus", "ok");
        return new QueueService.Item(r, "HASH" + id, 0, true);
    }

    @Test
    void queue_asSeerrAsksForIt_isAPageSortedByTimeLeft() throws Exception {
        when(queue.items(false, true, false)).thenReturn(List.of(item(1, "B", 200), item(2, "A", 100)));
        mvc.perform(get("/api/v3/queue").param("includeEpisode", "true").param("pageSize", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(1000))
                .andExpect(jsonPath("$.sortKey").value("timeleft"))
                .andExpect(jsonPath("$.sortDirection").value("ascending"))
                .andExpect(jsonPath("$.totalRecords").value(2))
                .andExpect(jsonPath("$.records[0].id").value(2))
                .andExpect(jsonPath("$.records[1].id").value(1));
    }

    @Test
    void queue_secondPage_holdsTheRest() throws Exception {
        when(queue.items(false, false, false)).thenReturn(List.of(item(1, "B", 200), item(2, "A", 100)));
        mvc.perform(get("/api/v3/queue").param("page", "2").param("pageSize", "1").param("sortKey", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRecords").value(2))
                .andExpect(jsonPath("$.records[0].title").value("B"));
    }

    @Test
    void details_isAList() throws Exception {
        when(queue.items(false, false, false)).thenReturn(List.of(item(1, "B", 200)));
        mvc.perform(get("/api/v3/queue/details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void status_isWhatTheQueueCounts() throws Exception {
        when(queue.status()).thenReturn(Map.of("totalCount", 0, "count", 0, "unknownCount", 0, "errors", false,
                "warnings", false));
        mvc.perform(get("/api/v3/queue/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.errors").value(false));
    }

    @Test
    void delete_removesFromTheClientByDefault() throws Exception {
        mvc.perform(delete("/api/v3/queue/7").param("blocklist", "true"))
                .andExpect(status().isOk());
        verify(queue).remove(eq(List.of(7)), eq(true), eq(true), eq(false), anyString());
    }
}
