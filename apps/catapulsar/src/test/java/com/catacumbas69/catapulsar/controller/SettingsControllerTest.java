package com.catacumbas69.catapulsar.controller;

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

import com.catacumbas69.catapulsar.service.CollectIntervalService;
import com.catacumbas69.catapulsar.service.InvalidSettingException;
import com.catacumbas69.catapulsar.service.CataponteEndpoint;
import com.catacumbas69.catapulsar.service.RefreshIntervalService;
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
    private CataponteEndpoint cataponte;

    @MockitoBean
    private RefreshIntervalService refresh;

    @MockitoBean
    private CollectIntervalService collect;

    @BeforeEach
    void ranges() {
        when(refresh.defaultValue()).thenReturn(10);
        when(refresh.min()).thenReturn(2);
        when(refresh.max()).thenReturn(300);
        when(collect.defaultValue()).thenReturn(60);
        when(collect.min()).thenReturn(15);
        when(collect.max()).thenReturn(600);
    }

    @Test
    void collectInterval_canBeReadChangedAndReset() throws Exception {
        when(collect.value()).thenReturn(60);
        when(collect.change(eq(120), any())).thenReturn(120);
        when(collect.reset(any())).thenReturn(60);

        mvc.perform(get("/api/v1/settings/collect-interval"))
                .andExpect(jsonPath("$.seconds").value(60))
                .andExpect(jsonPath("$.min").value(15))
                .andExpect(jsonPath("$.max").value(600))
                .andExpect(jsonPath("$.custom").value(false));
        mvc.perform(put("/api/v1/settings/collect-interval").header("X-Forwarded-For", "10.0.0.9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seconds").value(120))
                .andExpect(jsonPath("$.custom").value(true));
        mvc.perform(delete("/api/v1/settings/collect-interval")).andExpect(status().isOk());

        verify(collect).change(120, "10.0.0.9");
        verify(collect).reset(any());
    }

    @Test
    void collectInterval_returns400_outOfRange() throws Exception {
        when(collect.change(eq(5), any()))
                .thenThrow(new InvalidSettingException("seconds", "must be between 15 and 600"));

        mvc.perform(put("/api/v1/settings/collect-interval")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("seconds"));
    }

    @Test
    void refreshInterval_returnsValueDefaultAndRange() throws Exception {
        when(refresh.value()).thenReturn(30);

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

        mvc.perform(put("/api/v1/settings/refresh-interval").header("X-Forwarded-For", "10.0.0.9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seconds").value(30));

        verify(refresh).change(30, "10.0.0.9");
    }

    @Test
    void changeRefreshInterval_returns400_withoutSeconds() throws Exception {
        mvc.perform(put("/api/v1/settings/refresh-interval")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("seconds")));
        verify(refresh, never()).change(anyInt(), any());
    }

    @Test
    void resetRefreshInterval_goesBackToDefault() throws Exception {
        when(refresh.reset(any())).thenReturn(10);

        mvc.perform(delete("/api/v1/settings/refresh-interval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(false));
    }

    @BeforeEach
    void currentUrl() {
        when(cataponte.url()).thenReturn("http://localhost:8082");
        when(cataponte.defaultUrl()).thenReturn("http://cataponte:8082");
        when(cataponte.isCustom()).thenReturn(true);
    }

    @Test
    void get_returnsCurrentAndDefault() throws Exception {
        mvc.perform(get("/api/v1/settings/cataponte"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://localhost:8082"))
                .andExpect(jsonPath("$.defaultUrl").value("http://cataponte:8082"))
                .andExpect(jsonPath("$.custom").value(true));
    }

    @Test
    void put_changesTheUrl_withClientAddress() throws Exception {
        mvc.perform(put("/api/v1/settings/cataponte").header("X-Forwarded-For", "10.0.0.9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://localhost:8082\"}"))
                .andExpect(status().isOk());

        verify(cataponte).change("http://localhost:8082", "10.0.0.9");
    }

    @Test
    void put_returns400_forBlankUrl() throws Exception {
        mvc.perform(put("/api/v1/settings/cataponte")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("url")));
        verify(cataponte, never()).change(any(), any());
    }

    @Test
    void put_returns400_whenTheUrlIsRejected() throws Exception {
        when(cataponte.change(eq("ftp://x"), any())).thenThrow(
                new InvalidSettingException("url", "must start with http:// or https://"));

        mvc.perform(put("/api/v1/settings/cataponte")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"ftp://x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    @Test
    void put_returns503_whenMongoIsDown() throws Exception {
        when(cataponte.change(any(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        mvc.perform(put("/api/v1/settings/cataponte")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://x:1\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void delete_resetsToDefault() throws Exception {
        mvc.perform(delete("/api/v1/settings/cataponte"))
                .andExpect(status().isOk());

        verify(cataponte).reset(any());
    }
}
