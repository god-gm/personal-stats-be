package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.PlayerInfoDTO;
import com.godsofdeath.monitor.service.PlayerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/player")
@RequiredArgsConstructor
@Tag(name = "Player", description = "Info personali dal profilo Tacticus")
@SecurityRequirement(name = "bearerAuth")
public class PlayerController {

    private final PlayerService playerService;

    @GetMapping("/info")
    @Operation(summary = "Info operativo: nome API, token raid e bomba")
    public ResponseEntity<GenericResponseDTO<PlayerInfoDTO>> getPlayerInfo(
            @AuthenticationPrincipal String currentUserId) {

        GenericResponseDTO<PlayerInfoDTO> response = playerService.getPlayerInfo(currentUserId);
        int status = "OK".equals(response.getStatus()) ? 200 : 502;
        return ResponseEntity.status(status).body(response);
    }
}
