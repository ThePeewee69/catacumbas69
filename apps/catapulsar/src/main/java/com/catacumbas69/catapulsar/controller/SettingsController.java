package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catapulsar.dto.CountSettingRequest;
import com.catacumbas69.catapulsar.dto.CountSettingResponse;
import com.catacumbas69.catapulsar.dto.IntervalSettingRequest;
import com.catacumbas69.catapulsar.dto.IntervalSettingResponse;
import com.catacumbas69.catapulsar.dto.UrlSettingRequest;
import com.catacumbas69.catapulsar.dto.UrlSettingResponse;
import com.catacumbas69.catapulsar.service.CatametricasEndpoint;
import com.catacumbas69.catapulsar.service.CataponteEndpoint;
import com.catacumbas69.catapulsar.service.CatauditEndpoint;
import com.catacumbas69.catapulsar.service.CollectIntervalService;
import com.catacumbas69.catapulsar.service.DeadLetterDeliveriesService;
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
    private final CatauditEndpoint cataudit;
    private final CatametricasEndpoint catametricas;
    private final RefreshIntervalService refresh;
    private final CollectIntervalService collect;
    private final DeadLetterDeliveriesService deliveries;

    /**
     * Creates the controller.
     *
     * @param cataponte Cataponte base URL holder
     * @param cataudit Cataudit base URL holder
     * @param catametricas Catametricas base URL holder
     * @param refresh UI auto-refresh interval
     * @param collect Catametricas collection interval
     * @param deliveries deliveries before a dead-letter topic
     */
    public SettingsController(CataponteEndpoint cataponte, CatauditEndpoint cataudit,
                              CatametricasEndpoint catametricas, RefreshIntervalService refresh,
                              CollectIntervalService collect, DeadLetterDeliveriesService deliveries) {
        this.cataponte = cataponte;
        this.cataudit = cataudit;
        this.catametricas = catametricas;
        this.refresh = refresh;
        this.collect = collect;
        this.deliveries = deliveries;
    }

    /**
     * Returns the deliveries before a message goes to its dead-letter topic.
     *
     * @return number in use, default and accepted range (2–100)
     */
    @Operation(summary = "Deliveries before a dead-letter topic")
    @GetMapping("/dead-letters")
    public CountSettingResponse deadLetters() {
        return CountSettingResponse.of(deliveries, deliveries.value());
    }

    /**
     * Changes the deliveries before a dead-letter topic; Cataudit, Catametricas and Cataponte subscribe again.
     *
     * @param request new number
     * @param http current request, for the audit log
     * @return number in use, default and accepted range
     */
    @Operation(summary = "Change the deliveries before a dead-letter topic")
    @PutMapping("/dead-letters")
    public CountSettingResponse changeDeadLetters(@Valid @RequestBody CountSettingRequest request,
                                                  HttpServletRequest http) {
        return CountSettingResponse.of(deliveries, deliveries.change(request.value(), ClientAddress.of(http)));
    }

    /**
     * Goes back to the configured number of deliveries ({@code catacommons.consumers.max-deliveries}).
     *
     * @param http current request, for the audit log
     * @return number in use, default and accepted range
     */
    @Operation(summary = "Reset the deliveries before a dead-letter topic")
    @DeleteMapping("/dead-letters")
    public CountSettingResponse resetDeadLetters(HttpServletRequest http) {
        return CountSettingResponse.of(deliveries, deliveries.reset(ClientAddress.of(http)));
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
    public UrlSettingResponse cataponte() {
        return UrlSettingResponse.of(cataponte);
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
    public UrlSettingResponse changeCataponte(@Valid @RequestBody UrlSettingRequest request,
                                              HttpServletRequest http) {
        cataponte.change(request.url(), ClientAddress.of(http));
        return UrlSettingResponse.of(cataponte);
    }

    /**
     * Goes back to the configured Cataponte base URL ({@code CATAPONTE_URL}).
     *
     * @param http current request, for the audit log
     * @return current and default URL
     */
    @Operation(summary = "Reset the Cataponte URL to its configured default")
    @DeleteMapping("/cataponte")
    public UrlSettingResponse resetCataponte(HttpServletRequest http) {
        cataponte.reset(ClientAddress.of(http));
        return UrlSettingResponse.of(cataponte);
    }

    /**
     * Returns the Cataudit base URL in use.
     *
     * @return current and default URL
     */
    @Operation(summary = "Cataudit URL in use")
    @GetMapping("/cataudit")
    public UrlSettingResponse cataudit() {
        return UrlSettingResponse.of(cataudit);
    }

    /**
     * Changes the Cataudit base URL. Takes effect immediately.
     *
     * @param request new URL
     * @param http current request, for the audit log
     * @return current and default URL
     */
    @Operation(summary = "Change the Cataudit URL")
    @PutMapping("/cataudit")
    public UrlSettingResponse changeCataudit(@Valid @RequestBody UrlSettingRequest request,
                                             HttpServletRequest http) {
        cataudit.change(request.url(), ClientAddress.of(http));
        return UrlSettingResponse.of(cataudit);
    }

    /**
     * Goes back to the configured Cataudit base URL ({@code CATAUDIT_URL}).
     *
     * @param http current request, for the audit log
     * @return current and default URL
     */
    @Operation(summary = "Reset the Cataudit URL to its configured default")
    @DeleteMapping("/cataudit")
    public UrlSettingResponse resetCataudit(HttpServletRequest http) {
        cataudit.reset(ClientAddress.of(http));
        return UrlSettingResponse.of(cataudit);
    }

    /**
     * Returns the Catametricas base URL in use.
     *
     * @return current and default URL
     */
    @Operation(summary = "Catametricas URL in use")
    @GetMapping("/catametricas")
    public UrlSettingResponse catametricas() {
        return UrlSettingResponse.of(catametricas);
    }

    /**
     * Changes the Catametricas base URL. Takes effect immediately.
     *
     * @param request new URL
     * @param http current request, for the audit log
     * @return current and default URL
     */
    @Operation(summary = "Change the Catametricas URL")
    @PutMapping("/catametricas")
    public UrlSettingResponse changeCatametricas(@Valid @RequestBody UrlSettingRequest request,
                                                 HttpServletRequest http) {
        catametricas.change(request.url(), ClientAddress.of(http));
        return UrlSettingResponse.of(catametricas);
    }

    /**
     * Goes back to the configured Catametricas base URL ({@code CATAMETRICAS_URL}).
     *
     * @param http current request, for the audit log
     * @return current and default URL
     */
    @Operation(summary = "Reset the Catametricas URL to its configured default")
    @DeleteMapping("/catametricas")
    public UrlSettingResponse resetCatametricas(HttpServletRequest http) {
        catametricas.reset(ClientAddress.of(http));
        return UrlSettingResponse.of(catametricas);
    }
}
