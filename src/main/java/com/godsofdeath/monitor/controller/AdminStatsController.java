package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.GuildStatEntryDTO;
import com.godsofdeath.monitor.dto.output.TargetAssigneeDTO;
import com.godsofdeath.monitor.dto.output.TargetAttackStatDTO;
import com.godsofdeath.monitor.dto.output.TokenUsageEntryDTO;
import com.godsofdeath.monitor.service.AdminStatsService;
import com.godsofdeath.monitor.service.ExternalAssignmentService;
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

    private final AdminStatsService         adminStatsService;
    private final ExternalAssignmentService externalAssignmentService;

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

    @GetMapping("/target-consigliato")
    @Operation(summary = "Player con assegnazione Consigliato su un target (unitId + rarity) per l'assignment corrente")
    public ResponseEntity<GenericResponseDTO<List<TargetAssigneeDTO>>> getTargetConsigliato(
            @RequestParam String unitId,
            @RequestParam String rarity,
            @RequestParam(required = false) String levelDesc) {
        return ResponseEntity.ok(externalAssignmentService.getConsigliatoPlayers(unitId, rarity, levelDesc));
    }

    @GetMapping("/target-attacks")
    @Operation(summary = "Player che hanno attaccato un target (unitId + rarity) nella season corrente, con conteggio e media")
    public ResponseEntity<GenericResponseDTO<List<TargetAttackStatDTO>>> getTargetAttacks(
            @RequestParam String unitId,
            @RequestParam String rarity) {
        return ResponseEntity.ok(adminStatsService.getTargetAttackStats(unitId, rarity));
    }
}
