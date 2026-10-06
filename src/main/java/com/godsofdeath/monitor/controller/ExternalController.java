package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.service.ExternalAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API dedicata per sistemi esterni: nessuna autenticazione JWT (rotta permitAll in
 * SecurityConfig), protetta invece da uno shared secret applicativo passato via header.
 */
@RestController
@RequestMapping("/api/external")
@RequiredArgsConstructor
@Tag(name = "External", description = "API per sistemi esterni (autenticazione via API key applicativa)")
public class ExternalController {

    private final ExternalAssignmentService externalAssignmentService;

    @GetMapping("/boss-assignment")
    @Operation(summary = "Elenco userId dei player assegnati (consigliato) a un boss/mini per la season corrente")
    public ResponseEntity<GenericResponseDTO<List<String>>> getBossAssignment(
            @RequestHeader(value = "X-External-Api-Key", required = false) String apiKey,
            @Parameter(description = "Identificativo boss/mini, es. GuildBoss6Boss1TyranScreamerKiller")
            @RequestParam String bossIdentifier,
            @Parameter(description = "Livello stagionale opzionale (es. L5) per disambiguare boss configurati più volte con la stessa rarity")
            @RequestParam(required = false) String levelDesc) {

        if (!externalAssignmentService.isValidApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(GenericResponseDTO.denied("API key non valida"));
        }

        GenericResponseDTO<List<String>> response = externalAssignmentService.getAssignedPlayerIds(bossIdentifier, levelDesc);
        int status = "OK".equals(response.getStatus()) ? 200 : 400;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/boss-engageable")
    @Operation(summary = "Elenco userId dei player che hanno un boss/mini come affrontabile per la season corrente")
    public ResponseEntity<GenericResponseDTO<List<String>>> getBossEngageable(
            @RequestHeader(value = "X-External-Api-Key", required = false) String apiKey,
            @Parameter(description = "Identificativo boss/mini, es. GuildBoss6Boss1TyranScreamerKiller")
            @RequestParam String bossIdentifier,
            @Parameter(description = "Livello stagionale opzionale (es. L5) per disambiguare boss configurati più volte con la stessa rarity")
            @RequestParam(required = false) String levelDesc) {

        if (!externalAssignmentService.isValidApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(GenericResponseDTO.denied("API key non valida"));
        }

        GenericResponseDTO<List<String>> response = externalAssignmentService.getEngageablePlayerIds(bossIdentifier, levelDesc);
        int status = "OK".equals(response.getStatus()) ? 200 : 400;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/raid-data")
    @Operation(summary = "Proxy verso l'API Tacticus /guildRaid: autentica con TEMP_KEY e usa l'apiKey fornita per la chiamata")
    public ResponseEntity<Object> getRaidData(
            @RequestHeader(value = "X-External-Api-Key", required = false) String authKey,
            @Parameter(description = "API key Tacticus da usare per la chiamata alla guild raid")
            @RequestParam String apiKey) {

        if (!externalAssignmentService.isValidTempKey(authKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(GenericResponseDTO.denied("API key non valida"));
        }

        Map<String, Object> raidData = externalAssignmentService.fetchRawRaidData(apiKey);
        return ResponseEntity.ok(raidData);
    }
}
