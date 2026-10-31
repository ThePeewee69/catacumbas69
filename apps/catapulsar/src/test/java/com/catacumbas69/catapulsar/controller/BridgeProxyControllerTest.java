package com.catacumbas69.catapulsar.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.catapulsar.service.BridgeProxyService;
import com.catacumbas69.catapulsar.service.CataponteUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BridgeProxyController.class)
class BridgeProxyControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BridgeProxyService bridges;

    @Test
    void create_returnsCataponteResponseUnchanged() throws Exception {
        when(bridges.create(eq("{\"name\":\"x\"}"), any())).thenReturn(ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\"b1\"}".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(post("/api/v1/bridges").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("b1"));
    }

    @Test
    void returns503Problem_whenCataponteIsDown() throws Exception {
        when(bridges.list()).thenThrow(new CataponteUnavailableException(new IOException("Connection refused")));

        mvc.perform(get("/api/v1/bridges"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Cataponte unavailable"));
    }
}
