package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.SeasonPlayerDetailDTO;
import com.godsofdeath.monitor.dto.output.SeasonRankingDTO;
import com.godsofdeath.monitor.service.SeasonRankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Classifica stagionale per il pannello admin della dashboard")
@SecurityRequirement(name = "bearerAuth")
public class SeasonRankingController {

    private final SeasonRankingService seasonRankingService;

    @GetMapping("/season-ranking")
    @Operation(summary = "Classifica gilda per una stagione (delta cumulativo per attacco, Battle, tutti i Mythic + ultimi 2 target Legendary, no killing blow)")
    public ResponseEntity<GenericResponseDTO<SeasonRankingDTO>> getSeasonRanking(
            @RequestParam(required = false) Integer seasonNumber) {
        return ResponseEntity.ok(seasonRankingService.getSeasonRanking(seasonNumber));
    }

    @GetMapping("/season-ranking/by-type")
    @Operation(summary = "Classifica gilda per una stagione limitata ai soli attacchi su Boss (mode=BOSS) o Primes/SideBoss (mode=PRIMES), stesse regole della classifica totale")
    public ResponseEntity<GenericResponseDTO<SeasonRankingDTO>> getSeasonRankingByType(
            @RequestParam(required = false) Integer seasonNumber,
            @RequestParam String mode) {
        return ResponseEntity.ok(seasonRankingService.getSeasonRankingByType(seasonNumber, mode));
    }

    @GetMapping("/season-player-detail")
    @Operation(summary = "Storico incontri Battle (no bombe) di un giocatore in una stagione, con team e immagini")
    public ResponseEntity<GenericResponseDTO<SeasonPlayerDetailDTO>> getPlayerDetail(
            @RequestParam int    seasonNumber,
            @RequestParam String userId) {
        return ResponseEntity.ok(seasonRankingService.getPlayerDetail(seasonNumber, userId));
    }
}
