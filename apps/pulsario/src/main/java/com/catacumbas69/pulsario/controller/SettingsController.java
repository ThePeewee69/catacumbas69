package com.catacumbas69.pulsario.controller;

import com.catacumbas69.pulsario.config.PulsarioProperties;
import com.catacumbas69.pulsario.dto.PassarioSettingRequest;
import com.catacumbas69.pulsario.dto.PassarioSettingResponse;
import com.catacumbas69.pulsario.dto.RefreshIntervalRequest;
import com.catacumbas69.pulsario.dto.RefreshIntervalResponse;
import com.catacumbas69.pulsario.service.PassarioEndpoint;
import com.catacumbas69.pulsario.service.RefreshIntervalService;
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

    private final PassarioEndpoint passario;
    private final RefreshIntervalService refresh;

    /**
     * Creates the controller.
     *
     * @param passario Passário base URL holder
     * @param refresh UI auto-refresh interval
     */
    public SettingsController(PassarioEndpoint passario, RefreshIntervalService refresh) {
        this.passario = passario;
        this.refresh = refresh;
    }

    /**
     * Returns the UI auto-refresh interval.
     *
     * @return interval in use, default and accepted range
     */
    @Operation(summary = "UI auto-refresh interval")
    @GetMapping("/refresh-interval")
    public RefreshIntervalResponse refreshInterval() {
        return refreshResponse(refresh.seconds());
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
    public RefreshIntervalResponse changeRefreshInterval(@Valid @RequestBody RefreshIntervalRequest request,
                                                         HttpServletRequest http) {
        return refreshResponse(refresh.change(request.seconds(), ClientAddress.of(http)));
    }

    /**
     * Goes back to the configured auto-refresh interval ({@code PULSARIO_REFRESH_INTERVAL_SECONDS}).
     *
     * @param http current request, for the audit log
     * @return interval in use, default and accepted range
     */
    @Operation(summary = "Reset the UI auto-refresh interval to its configured default")
    @DeleteMapping("/refresh-interval")
    public RefreshIntervalResponse resetRefreshInterval(HttpServletRequest http) {
        return refreshResponse(refresh.reset(ClientAddress.of(http)));
    }

    /**
     * Returns the Passário base URL in use.
     *
     * @return current and default URL
     */
    @Operation(summary = "Passário URL in use")
    @GetMapping("/passario")
    public PassarioSettingResponse passario() {
        return response();
    }

    /**
     * Changes the Passário base URL. Takes effect immediately.
     *
     * @param request new URL
     * @param http current request, for the audit log
     * @return current and default URL
     */
    @Operation(summary = "Change the Passário URL")
    @PutMapping("/passario")
    public PassarioSettingResponse changePassario(@Valid @RequestBody PassarioSettingRequest request,
                                                  HttpServletRequest http) {
        passario.change(request.url(), ClientAddress.of(http));
        return response();
    }

    /**
     * Goes back to the configured Passário base URL ({@code PASSARIO_URL}).
     *
     * @param http current request, for the audit log
     * @return current and default URL
     */
    @Operation(summary = "Reset the Passário URL to its configured default")
    @DeleteMapping("/passario")
    public PassarioSettingResponse resetPassario(HttpServletRequest http) {
        passario.reset(ClientAddress.of(http));
        return response();
    }

    private RefreshIntervalResponse refreshResponse(int seconds) {
        return new RefreshIntervalResponse(seconds, refresh.defaultSeconds(), PulsarioProperties.MIN_REFRESH_SECONDS,
                PulsarioProperties.MAX_REFRESH_SECONDS, seconds != refresh.defaultSeconds());
    }

    private PassarioSettingResponse response() {
        return new PassarioSettingResponse(passario.url(), passario.defaultUrl(), passario.isCustom());
    }
}
