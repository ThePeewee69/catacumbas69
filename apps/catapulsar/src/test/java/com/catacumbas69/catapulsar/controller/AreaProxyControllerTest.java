package com.catacumbas69.catapulsar.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catapulsar.service.BridgeProxyService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AreaProxyController.class)
class AreaProxyControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BridgeProxyService bridges;

    @Test
    void list_returnsCataponteResponseUnchanged() throws Exception {
        when(bridges.listAreas()).thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body("[{\"id\":\"a1\",\"bridgeCount\":2}]".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/v1/areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bridgeCount").value(2));
    }

    @Test
    void delete_forwardsTheChoice() throws Exception {
        when(bridges.deleteArea(eq("a1"), eq("delete"), isNull(), any()))
                .thenReturn(ResponseEntity.noContent().build());

        mvc.perform(delete("/api/v1/areas/a1").param("bridges", "delete"))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_refusesAnUnknownChoice() throws Exception {
        mvc.perform(delete("/api/v1/areas/a1").param("bridges", "keep"))
                .andExpect(status().isBadRequest());

        verify(bridges, never()).deleteArea(any(), any(), any(), any());
    }
}
