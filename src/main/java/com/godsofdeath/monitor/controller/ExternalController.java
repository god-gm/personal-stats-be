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
    @Operation(summary = "Elenco discord_name assegnati (consigliato) a un boss/mini per la season corrente")
    public ResponseEntity<GenericResponseDTO<List<String>>> getBossAssignment(
            @RequestHeader(value = "X-External-Api-Key", required = false) String apiKey,
            @Parameter(description = "Identificativo boss/mini, es. GuildBoss6Boss1TyranScreamerKiller")
            @RequestParam String bossIdentifier) {

        if (!externalAssignmentService.isValidApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(GenericResponseDTO.denied("API key non valida"));
        }

        GenericResponseDTO<List<String>> response = externalAssignmentService.getAssignedDiscordNames(bossIdentifier);
        int status = "OK".equals(response.getStatus()) ? 200 : 400;
        return ResponseEntity.status(status).body(response);
    }
}
