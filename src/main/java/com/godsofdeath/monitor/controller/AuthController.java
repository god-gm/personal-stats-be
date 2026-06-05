package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.input.DiscordCallbackInputDTO;
import com.godsofdeath.monitor.dto.input.LoginInputDTO;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.LoginDataDTO;
import com.godsofdeath.monitor.service.AuthService;
import com.godsofdeath.monitor.service.DiscordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Autenticazione tramite username Discord")
public class AuthController {

    private final AuthService authService;
    private final DiscordService discordService;

    @PostMapping("/login")
    @Operation(summary = "Login tramite username Discord")
    public ResponseEntity<GenericResponseDTO<LoginDataDTO>> login(
            @Valid @RequestBody LoginInputDTO input) {

        GenericResponseDTO<LoginDataDTO> response = authService.login(input.getDiscordName());

        int status = "OK".equals(response.getStatus()) ? 200 : 401;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/discord/callback")
    @Operation(summary = "Login tramite OAuth2 Discord (Authorization Code flow)")
    public ResponseEntity<GenericResponseDTO<LoginDataDTO>> discordCallback(
            @Valid @RequestBody DiscordCallbackInputDTO input) {

        GenericResponseDTO<LoginDataDTO> response = discordService.handleCallback(input.getCode());

        int status = "OK".equals(response.getStatus()) ? 200 : 401;
        return ResponseEntity.status(status).body(response);
    }
}
