package com.catacumbas69.cataponte.controller;

import static com.catacumbas69.cataponte.TestBridges.DEST_A;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.cataponte.TestBridges;
import com.catacumbas69.cataponte.dto.BridgeMapper;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.model.BridgeTrafficTotal;
import com.catacumbas69.cataponte.model.TrafficRole;
import com.catacumbas69.cataponte.service.BridgeConflictException;
import com.catacumbas69.cataponte.service.BridgeManager;
import com.catacumbas69.cataponte.service.BridgeNotFoundException;
import com.catacumbas69.cataponte.service.BridgeService;
import com.catacumbas69.cataponte.service.BridgeValidationException;
import com.catacumbas69.cataponte.service.TrafficService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BridgeController.class)
@Import(BridgeMapper.class)
class BridgeControllerTest {

    private static final String VALID_BODY = """
            {
              "name": "orders-fanout",
              "source": {"topic": "persistent://home/media/in", "subscription": "cataponte-orders"},
              "destinations": [{"topic": "persistent://home/media/out-a"}]
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BridgeService service;

    @MockitoBean
    private BridgeManager manager;

    @MockitoBean
    private TrafficService traffic;

    @Test
    void traffic_returnsTotalsPerTopic() throws Exception {
        BridgeTrafficTotal total = new BridgeTrafficTotal();
        total.setBridgeId("abc");
        total.setBridgeName("orders-fanout");
        total.setRole(TrafficRole.SOURCE);
        total.setTopic(TestBridges.SOURCE);
        total.setMessages(42);
        total.setBytes(420);
        when(traffic.totals("abc")).thenReturn(List.of(total));

        mvc.perform(get("/api/v1/bridges/abc/traffic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("SOURCE"))
                .andExpect(jsonPath("$[0].messages").value(42));
    }

    @Test
    void trafficMinutes_returns400_whenRangeIsInvalid() throws Exception {
        when(traffic.minutes(eq("abc"), any(), any()))
                .thenThrow(new BridgeValidationException("from", "must be before 'to'"));

        mvc.perform(get("/api/v1/bridges/abc/traffic/minutes")
                        .param("from", "2026-10-30T10:00:00Z").param("to", "2026-10-30T09:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    @Test
    void create_returns201WithLocation() throws Exception {
        BridgeDocument stored = TestBridges.ordered("abc", DEST_A);
        when(service.create(any(), any())).thenReturn(stored);

        mvc.perform(post("/api/v1/bridges").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/bridges/abc"))
                .andExpect(jsonPath("$.id").value("abc"))
                .andExpect(jsonPath("$.destinations[0].topic").value(DEST_A))
                .andExpect(jsonPath("$.ordered").value(true));
    }

    @Test
    void create_returnsProblemDetailsWithFieldErrors_whenBodyIsInvalid() throws Exception {
        String invalid = """
                {"name": "", "source": {"topic": "orders", "subscription": "s"}, "destinations": []}
                """;

        mvc.perform(post("/api/v1/bridges").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("source.topic")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("destinations")));
    }

    @Test
    void create_returns400_whenCrossFieldRuleFails() throws Exception {
        when(service.create(any(), any()))
                .thenThrow(new BridgeValidationException("ordered", "Ordered needs Failover"));

        mvc.perform(post("/api/v1/bridges").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Ordered needs Failover"))
                .andExpect(jsonPath("$.errors[0].field").value("ordered"));
    }

    @Test
    void get_returns404Problem_whenBridgeIsMissing() throws Exception {
        when(service.get("nope")).thenThrow(new BridgeNotFoundException("nope"));

        mvc.perform(get("/api/v1/bridges/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bridge not found"));
    }

    @Test
    void update_returns409_onStaleVersion() throws Exception {
        when(service.update(eq("abc"), any(), isNull(), any())).thenThrow(new BridgeConflictException("stale"));

        mvc.perform(put("/api/v1/bridges/abc").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("stale"));
    }

    @Test
    void enable_returnsBridge() throws Exception {
        when(service.setEnabled(eq("abc"), anyBoolean(), any())).thenReturn(TestBridges.unordered("abc", DEST_A));

        mvc.perform(post("/api/v1/bridges/abc/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void status_reportsRunningCount() throws Exception {
        when(manager.getRunningCount()).thenReturn(2);
        when(manager.getStatuses()).thenReturn(List.of());

        mvc.perform(get("/api/v1/bridges/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(2));
    }
}
