package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.PlayerTokenBreakdownResultDTO;
import com.godsofdeath.monitor.service.PlayerPerformancesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Statistiche gilda per il pannello admin della dashboard")
@SecurityRequirement(name = "bearerAuth")
public class PlayerPerformancesController {

    private final PlayerPerformancesService playerPerformancesService;

    @GetMapping("/player-token-breakdown")
    @Operation(summary = "Breakdown token per player nella season corrente classificati per tipo assignment (consigliato/affrontabile/sconsigliato)")
    public ResponseEntity<GenericResponseDTO<PlayerTokenBreakdownResultDTO>> getPlayerTokenBreakdown() {
        return ResponseEntity.ok(playerPerformancesService.getPlayerTokenBreakdown());
    }
}
