package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.output.AnagBossDTO;
import com.godsofdeath.monitor.dto.output.AnagLevelDTO;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.repository.AnagBossRepository;
import com.godsofdeath.monitor.repository.AnagLevelRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/anag")
@RequiredArgsConstructor
@Tag(name = "Anag", description = "Anagrafiche livelli e boss")
@SecurityRequirement(name = "bearerAuth")
public class AnagController {

    private final AnagLevelRepository anagLevelRepository;
    private final AnagBossRepository  anagBossRepository;

    @GetMapping("/levels")
    @Operation(summary = "Elenco livelli ordinati per ID")
    public ResponseEntity<GenericResponseDTO<List<AnagLevelDTO>>> getLevels() {
        List<AnagLevelDTO> levels = anagLevelRepository.findAllOrderedById()
                .stream()
                .map(d -> AnagLevelDTO.builder()
                        .id(d.getId())
                        .descrizione(d.getDescrizione())
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(GenericResponseDTO.ok("Livelli recuperati", levels));
    }

    @GetMapping("/bosses")
    @Operation(summary = "Elenco boss ordinati per ID")
    public ResponseEntity<GenericResponseDTO<List<AnagBossDTO>>> getBosses() {
        List<AnagBossDTO> bosses = anagBossRepository.findAllOrderedById()
                .stream()
                .map(d -> AnagBossDTO.builder()
                        .id(d.getId())
                        .descrizione(d.getDescrizione())
                        .replayLink(d.getReplayLink())
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(GenericResponseDTO.ok("Boss recuperati", bosses));
    }
}
