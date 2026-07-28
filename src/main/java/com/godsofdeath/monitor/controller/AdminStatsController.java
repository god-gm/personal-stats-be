package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.GuildStatEntryDTO;
import com.godsofdeath.monitor.dto.output.TokenUsageEntryDTO;
import com.godsofdeath.monitor.service.AdminStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Statistiche gilda per il pannello admin della dashboard")
@SecurityRequirement(name = "bearerAuth")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @GetMapping("/token-usage")
    @Operation(summary = "Token usati nella season corrente per tutti i player (tutti i boss, no bombe)")
    public ResponseEntity<GenericResponseDTO<List<TokenUsageEntryDTO>>> getTokenUsage() {
        return ResponseEntity.ok(adminStatsService.getTokenUsage());
    }

    @GetMapping("/guild-stats")
    @Operation(summary = "Media danno overall nella season corrente per tutti i player (Legendary/Mythic, no bombe, no killing blow)")
    public ResponseEntity<GenericResponseDTO<List<GuildStatEntryDTO>>> getGuildStats() {
        return ResponseEntity.ok(adminStatsService.getGuildStats());
    }
}
