package com.catacumbas69.pulsario.controller;

import com.catacumbas69.pulsario.dto.PassarioSettingRequest;
import com.catacumbas69.pulsario.dto.PassarioSettingResponse;
import com.catacumbas69.pulsario.service.PassarioEndpoint;
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

    /**
     * Creates the controller.
     *
     * @param passario Passário base URL holder
     */
    public SettingsController(PassarioEndpoint passario) {
        this.passario = passario;
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

    private PassarioSettingResponse response() {
        return new PassarioSettingResponse(passario.url(), passario.defaultUrl(), passario.isCustom());
    }
}
