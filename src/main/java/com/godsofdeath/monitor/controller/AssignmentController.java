package com.godsofdeath.monitor.controller;

import com.godsofdeath.monitor.dto.input.AssignmentConfigInputDTO;
import com.godsofdeath.monitor.dto.input.SaveAssignmentInputDTO;
import com.godsofdeath.monitor.dto.output.AssignmentStatsDTO;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.SavedAssignmentListDTO;
import com.godsofdeath.monitor.service.AssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/assignments")
@RequiredArgsConstructor
@Tag(name = "Assignments", description = "Calcolo e gestione assignments season raid")
@SecurityRequirement(name = "bearerAuth")
public class AssignmentController {

    private final AssignmentService assignmentService;

    @PostMapping("/stats")
    @Operation(summary = "Calcola stats delle ultime 5 season per i boss configurati")
    public ResponseEntity<GenericResponseDTO<AssignmentStatsDTO>> computeStats(
            @RequestBody AssignmentConfigInputDTO input) {
        return ResponseEntity.ok(assignmentService.computeStats(input));
    }

    @GetMapping("/list")
    @Operation(summary = "Ultimi 3 assignment salvati")
    public ResponseEntity<GenericResponseDTO<List<SavedAssignmentListDTO>>> listSaved() {
        return ResponseEntity.ok(assignmentService.listSavedAssignments());
    }

    @PostMapping("/save")
    @Operation(summary = "Salva un assignment (con verifica sovrascrittura se il nome esiste)")
    public ResponseEntity<GenericResponseDTO<Void>> save(
            @RequestBody SaveAssignmentInputDTO input) {
        return ResponseEntity.ok(assignmentService.saveAssignment(input));
    }

    @GetMapping("/load")
    @Operation(summary = "Carica un assignment salvato per nome e season")
    public ResponseEntity<GenericResponseDTO<String>> load(
            @RequestParam String name,
            @RequestParam int   seasonNumber) {
        return ResponseEntity.ok(assignmentService.loadAssignment(name, seasonNumber));
    }

    @GetMapping("/exists")
    @Operation(summary = "Verifica se esiste già un assignment con quel nome e season")
    public ResponseEntity<GenericResponseDTO<Boolean>> exists(
            @RequestParam String name,
            @RequestParam int   seasonNumber) {
        boolean found = assignmentService.existsAssignment(name, seasonNumber);
        return ResponseEntity.ok(GenericResponseDTO.ok("check", found));
    }

    @GetMapping("/hidden-sides")
    @Operation(summary = "Restituisce i side keys nascosti per un assignment")
    public ResponseEntity<GenericResponseDTO<List<String>>> hiddenSides(
            @RequestParam String name) {
        return ResponseEntity.ok(assignmentService.loadHiddenSides(name));
    }
}
