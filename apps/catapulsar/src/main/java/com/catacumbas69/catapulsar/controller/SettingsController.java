package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catapulsar.dto.CataponteSettingRequest;
import com.catacumbas69.catapulsar.dto.CataponteSettingResponse;
import com.catacumbas69.catapulsar.dto.IntervalSettingRequest;
import com.catacumbas69.catapulsar.dto.IntervalSettingResponse;
import com.catacumbas69.catapulsar.service.CollectIntervalService;
import com.catacumbas69.catapulsar.service.CataponteEndpoint;
import com.catacumbas69.catapulsar.service.RefreshIntervalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime settings changed from the UI. Changes are stored in MongoDB and audited.
 */
@Tag(name = "Settings")
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final CataponteEndpoint cataponte;
    private final RefreshIntervalService refresh;
    private final CollectIntervalService collect;

    /**
     * Creates the controller.
     *
     * @param cataponte Cataponte base URL holder
     * @param refresh UI auto-refresh interval
     * @param collect Catametricas collection interval
     */
    public SettingsController(CataponteEndpoint cataponte, RefreshIntervalService refresh,
                              CollectIntervalService collect) {
        this.cataponte = cataponte;
        this.refresh = refresh;
        this.collect = collect;
    }

    /**
     * Returns the UI auto-refresh interval.
     *
     * @return interval in use, default and accepted range (2–300 s)
     */
    @Operation(summary = "UI auto-refresh interval")
    @GetMapping("/refresh-interval")
    public IntervalSettingResponse refreshInterval() {
        return IntervalSettingResponse.of(refresh, refresh.value());
    }

    /**
     * Changes the UI auto-refresh interval for every user.
     *
     * @param request new interval
     * @param http current request, for the audit log
     * @return interval in use, default and accepted range
     */
    @Operation(summary = "Change the UI auto-refresh interval")
    @PutMapping("/refresh-interval")
    public IntervalSettingResponse changeRefreshInterval(@Valid @RequestBody IntervalSettingRequest request,
                                                         HttpServletRequest http) {
        return IntervalSettingResponse.of(refresh, refresh.change(request.seconds(), ClientAddress.of(http)));
    }

    /**
     * Goes back to the configured auto-refresh interval ({@code CATAPULSAR_REFRESH_INTERVAL_SECONDS}).
     *
     * @param http current request, for the audit log
     * @return interval in use, default and accepted range
     */
    @Operation(summary = "Reset the UI auto-refresh interval to its configured default")
    @DeleteMapping("/refresh-interval")
    public IntervalSettingResponse resetRefreshInterval(HttpServletRequest http) {
        return IntervalSettingResponse.of(refresh, refresh.reset(ClientAddress.of(http)));
    }

    /**
     * Returns how often Catametricas collects Pulsar statistics.
     *
     * @return interval in use, default and accepted range (15–600 s)
     */
    @Operation(summary = "Catametricas collection interval")
    @GetMapping("/collect-interval")
    public IntervalSettingResponse collectInterval() {
        return IntervalSettingResponse.of(collect, collect.value());
    }

    /**
     * Changes how often Catametricas collects; Catametricas applies it from its next collection.
     *
     * @param request new interval
     * @param http current request, for the audit log
     * @return interval in use, default and accepted range
     */
    @Operation(summary = "Change the Catametricas collection interval")
    @PutMapping("/collect-interval")
    public IntervalSettingResponse changeCollectInterval(@Valid @RequestBody IntervalSettingRequest request,
                                                         HttpServletRequest http) {
        return IntervalSettingResponse.of(collect, collect.change(request.seconds(), ClientAddress.of(http)));
    }

    /**
     * Goes back to the configured collection interval ({@code CATAMETRICAS_COLLECT_INTERVAL_SECONDS}).
     *
     * @param http current request, for the audit log
     * @return interval in use, default and accepted range
     */
    @Operation(summary = "Reset the Catametricas collection interval to its configured default")
    @DeleteMapping("/collect-interval")
    public IntervalSettingResponse resetCollectInterval(HttpServletRequest http) {
        return IntervalSettingResponse.of(collect, collect.reset(ClientAddress.of(http)));
    }

    /**
     * Returns the Cataponte base URL in use.
     *
     * @return current and default URL
     */
    @Operation(summary = "Cataponte URL in use")
    @GetMapping("/cataponte")
    public CataponteSettingResponse cataponte() {
        return response();
    }

    /**
     * Changes the Cataponte base URL. Takes effect immediately.
     *
     * @param request new URL
     * @param http current request, for the audit log
     * @return current and default URL
     */
    @Operation(summary = "Change the Cataponte URL")
    @PutMapping("/cataponte")
    public CataponteSettingResponse changeCataponte(@Valid @RequestBody CataponteSettingRequest request,
                                                  HttpServletRequest http) {
        cataponte.change(request.url(), ClientAddress.of(http));
        return response();
    }

    /**
     * Goes back to the configured Cataponte base URL ({@code CATAPONTE_URL}).
     *
     * @param http current request, for the audit log
     * @return current and default URL
     */
    @Operation(summary = "Reset the Cataponte URL to its configured default")
    @DeleteMapping("/cataponte")
    public CataponteSettingResponse resetCataponte(HttpServletRequest http) {
        cataponte.reset(ClientAddress.of(http));
        return response();
    }

    private CataponteSettingResponse response() {
        return new CataponteSettingResponse(cataponte.url(), cataponte.defaultUrl(), cataponte.isCustom());
    }
}
