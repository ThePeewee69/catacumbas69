package com.catacumbas69.cataponte.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.cataponte.model.AreaDocument;
import com.catacumbas69.cataponte.service.AreaService;
import com.catacumbas69.cataponte.service.AreaService.BridgesOnDelete;
import com.catacumbas69.cataponte.service.BridgeConflictException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AreaController.class)
class AreaControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AreaService service;

    @Test
    void list_returnsEveryAreaWithItsBridgeCount() throws Exception {
        when(service.findAll()).thenReturn(List.of(area("a1", "media"), area("a2", "sensors")));
        when(service.bridgeCounts()).thenReturn(Map.of("a1", 2L));

        mvc.perform(get("/api/v1/areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("media"))
                .andExpect(jsonPath("$[0].patterns[0]").value("home/media/*"))
                .andExpect(jsonPath("$[0].bridgeCount").value(2))
                .andExpect(jsonPath("$[1].bridgeCount").value(0));
    }

    @Test
    void create_returns201_withLocation() throws Exception {
        when(service.create(any(), eq("10.0.0.9"))).thenReturn(area("a1", "media"));

        mvc.perform(post("/api/v1/areas").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "10.0.0.9")
                        .content("{\"name\":\"media\",\"patterns\":[\"home/media/*\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/areas/a1")));
    }

    @Test
    void create_refusesAnInvalidName() throws Exception {
        mvc.perform(post("/api/v1/areas").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad name\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_passesTheChoiceForItsBridges() throws Exception {
        mvc.perform(delete("/api/v1/areas/a1").param("bridges", "move").param("to", "a2"))
                .andExpect(status().isNoContent());

        verify(service).delete(eq("a1"), eq(BridgesOnDelete.MOVE), eq("a2"), any());
    }

    @Test
    void delete_withoutAChoice_answers409_whenTheAreaHasBridges() throws Exception {
        doThrow(new BridgeConflictException("Area 'media' has 2 bridge(s)"))
                .when(service).delete(eq("a1"), isNull(), isNull(), any());

        mvc.perform(delete("/api/v1/areas/a1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("2 bridge(s)")));
    }

    @Test
    void delete_refusesAnUnknownChoice() throws Exception {
        mvc.perform(delete("/api/v1/areas/a1").param("bridges", "keep"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("delete or move")));
    }

    private static AreaDocument area(String id, String name) {
        AreaDocument area = new AreaDocument();
        area.setId(id);
        area.setName(name);
        area.setPatterns(List.of("home/" + name + "/*"));
        return area;
    }
}
