package com.catacumbas69.pulsario.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catacumbas69.pulsario.service.InvalidSettingException;
import com.catacumbas69.pulsario.service.PassarioEndpoint;
import com.catacumbas69.pulsario.service.RefreshIntervalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PassarioEndpoint passario;

    @MockitoBean
    private RefreshIntervalService refresh;

    @Test
    void refreshInterval_returnsValueDefaultAndRange() throws Exception {
        when(refresh.seconds()).thenReturn(30);
        when(refresh.defaultSeconds()).thenReturn(10);

        mvc.perform(get("/api/v1/settings/refresh-interval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seconds").value(30))
                .andExpect(jsonPath("$.defaultSeconds").value(10))
                .andExpect(jsonPath("$.min").value(2))
                .andExpect(jsonPath("$.max").value(300))
                .andExpect(jsonPath("$.custom").value(true));
    }

    @Test
    void changeRefreshInterval_storesWithClientAddress() throws Exception {
        when(refresh.change(eq(30), any())).thenReturn(30);
        when(refresh.defaultSeconds()).thenReturn(10);

        mvc.perform(put("/api/v1/settings/refresh-interval").header("X-Forwarded-For", "10.0.0.9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seconds").value(30));

        verify(refresh).change(30, "10.0.0.9");
    }

    @Test
    void changeRefreshInterval_returns400_outOfRange() throws Exception {
        mvc.perform(put("/api/v1/settings/refresh-interval")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("seconds")));
        verify(refresh, never()).change(anyInt(), any());
    }

    @Test
    void resetRefreshInterval_goesBackToDefault() throws Exception {
        when(refresh.reset(any())).thenReturn(10);
        when(refresh.defaultSeconds()).thenReturn(10);

        mvc.perform(delete("/api/v1/settings/refresh-interval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(false));
    }

    @BeforeEach
    void currentUrl() {
        when(passario.url()).thenReturn("http://localhost:8082");
        when(passario.defaultUrl()).thenReturn("http://passario:8082");
        when(passario.isCustom()).thenReturn(true);
    }

    @Test
    void get_returnsCurrentAndDefault() throws Exception {
        mvc.perform(get("/api/v1/settings/passario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://localhost:8082"))
                .andExpect(jsonPath("$.defaultUrl").value("http://passario:8082"))
                .andExpect(jsonPath("$.custom").value(true));
    }

    @Test
    void put_changesTheUrl_withClientAddress() throws Exception {
        mvc.perform(put("/api/v1/settings/passario").header("X-Forwarded-For", "10.0.0.9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://localhost:8082\"}"))
                .andExpect(status().isOk());

        verify(passario).change("http://localhost:8082", "10.0.0.9");
    }

    @Test
    void put_returns400_forBlankUrl() throws Exception {
        mvc.perform(put("/api/v1/settings/passario")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("url")));
        verify(passario, never()).change(any(), any());
    }

    @Test
    void put_returns400_whenTheUrlIsRejected() throws Exception {
        when(passario.change(eq("ftp://x"), any())).thenThrow(
                new InvalidSettingException("url", "must start with http:// or https://"));

        mvc.perform(put("/api/v1/settings/passario")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"ftp://x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    @Test
    void put_returns503_whenMongoIsDown() throws Exception {
        when(passario.change(any(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        mvc.perform(put("/api/v1/settings/passario")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://x:1\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void delete_resetsToDefault() throws Exception {
        mvc.perform(delete("/api/v1/settings/passario"))
                .andExpect(status().isOk());

        verify(passario).reset(any());
    }
}
