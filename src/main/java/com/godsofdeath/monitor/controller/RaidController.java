package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.output.CurrentSeasonDataDTO;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.service.RaidService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/raid")
@RequiredArgsConstructor
@Tag(name = "Raid", description = "Statistiche raid di gilda")
@SecurityRequirement(name = "bearerAuth")
public class RaidController {

    private final RaidService raidService;

    @GetMapping("/current-season")
    @Operation(summary = "Statistiche stagione corrente per il giocatore autenticato")
    public ResponseEntity<GenericResponseDTO<CurrentSeasonDataDTO>> getCurrentSeason(
            @AuthenticationPrincipal String currentUserId) {

        return ResponseEntity.ok(raidService.getCurrentSeason(currentUserId));
    }
}
