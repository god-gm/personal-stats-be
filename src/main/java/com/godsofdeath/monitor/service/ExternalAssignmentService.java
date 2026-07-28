package com.godsofdeath.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.godsofdeath.monitor.document.AssignmentDocument;
import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.repository.AssignmentRepository;
import com.godsofdeath.monitor.repository.BossLookupRepository;
import com.godsofdeath.monitor.repository.PlayerRepository;
import com.godsofdeath.monitor.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Espone verso sistemi esterni la risoluzione "boss identifier → discord_name assegnati"
 * per l'ultimo assignment salvato della season corrente. Servizio indipendente e
 * dedicato: non riusa/modifica RaidService o AssignmentService.
 * <p>
 * Risoluzione del bossIdentifier: stesso approccio "fuzzy by name" già usato altrove nel
 * codebase (RaidService.resolveBossName / AssignmentService.resolveName + buildUnitNameToTypeMap),
 * mai un confronto puntuale sui campi anagrafica — CODIFICA è nota per essere talvolta
 * disallineata (vedi i commenti su "CODIFICA mal configurata" in RaidService). Si risolve
 * prima l'unitId a un nome via BossLookupRepository (match esatto, poi "contains" col più
 * lungo), poi si confronta quel nome, in LIKE bidirezionale, con bossDesc/mini.name già
 * salvati nell'assignment corrente (che a loro volta derivano da sp_anag_bosses.DESCRIZIONE
 * al momento del calcolo, quindi restano "l'anagrafica boss" richiesta, solo attraverso la
 * copia coerente già persistita in quell'assignment).
 */
@Service
@RequiredArgsConstructor
public class ExternalAssignmentService {

    private final BossLookupRepository bossLookupRepository;
    private final AssignmentRepository assignmentRepository;
    private final PlayerRepository     playerRepository;
    private final SysConfigRepository  sysConfigRepository;
    private final ObjectMapper         objectMapper;

    @Value("${tacticus.api.base-url}")
    private String tacticusBaseUrl;

    public GenericResponseDTO<List<String>> getAssignedDiscordNames(String bossIdentifier) {
        if (bossIdentifier == null || bossIdentifier.isBlank()) {
            return GenericResponseDTO.ko("bossIdentifier mancante");
        }

        String resolvedName = bossLookupRepository.findNameByUnitId(bossIdentifier)
                .or(() -> bossLookupRepository.findNameByUnitIdContains(bossIdentifier))
                .orElse(bossIdentifier);

        int season = fetchCurrentSeason();
        Optional<AssignmentDocument> assignmentDoc = assignmentRepository.findLatestBySeason(season);
        if (assignmentDoc.isEmpty()) {
            return GenericResponseDTO.ok("Nessun assignment salvato per la season corrente", List.of());
        }

        try {
            JsonNode root = objectMapper.readTree(assignmentDoc.get().getAssignmentData());
            JsonNode bossesNode = root.path("stats").path("bosses");
            if (!bossesNode.isArray()) {
                return GenericResponseDTO.ko("Boss non riconosciuto in anagrafica");
            }

            String targetKey = resolveTargetKey(resolvedName, bossesNode);
            if (targetKey == null) {
                return GenericResponseDTO.ko("Boss non riconosciuto in anagrafica");
            }

            List<String> discordNames = collectConsigliatoDiscordNames(root, targetKey);
            return GenericResponseDTO.ok("Assegnazioni recuperate", discordNames);
        } catch (Exception e) {
            return GenericResponseDTO.ko("Boss non riconosciuto in anagrafica");
        }
    }

    /**
     * Confronto "LIKE" bidirezionale (stesso pattern di AssignmentService.buildUnitNameToTypeMap)
     * tra il nome risolto e bossDesc/mini.name già salvati nell'assignment; vince il match più
     * lungo, per evitare ambiguità tra nomi che si contengono a vicenda.
     */
    private String resolveTargetKey(String resolvedName, JsonNode bossesNode) {
        String needle = resolvedName.toLowerCase();
        String bestKey = null;
        int    bestLength = -1;

        for (JsonNode b : bossesNode) {
            int    levelId  = b.path("levelId").asInt();
            String apiType  = b.path("apiType").asText("");
            String bossDesc = b.path("bossDesc").asText("");

            if (!bossDesc.isBlank()) {
                String hay = bossDesc.toLowerCase();
                if ((hay.contains(needle) || needle.contains(hay)) && hay.length() > bestLength) {
                    bestKey    = levelId + "_" + apiType;
                    bestLength = hay.length();
                }
            }

            JsonNode minisNode = b.get("minis");
            if (minisNode != null && minisNode.isArray()) {
                for (JsonNode m : minisNode) {
                    String miniName   = m.path("name").asText("");
                    String miniUnitId = m.path("unitId").asText("");
                    if (miniName.isBlank() || miniUnitId.isBlank()) continue;

                    String hay = miniName.toLowerCase();
                    if ((hay.contains(needle) || needle.contains(hay)) && hay.length() > bestLength) {
                        bestKey    = levelId + "_" + apiType + "__" + miniUnitId;
                        bestLength = hay.length();
                    }
                }
            }
        }
        return bestKey;
    }

    private List<String> collectConsigliatoDiscordNames(JsonNode root, String targetKey) {
        JsonNode assignmentsNode = root.get("assignments");
        if (assignmentsNode == null || !assignmentsNode.isObject()) return List.of();

        List<String> discordNames = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = assignmentsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode targetNode = e.getValue().get(targetKey);
            if (targetNode == null || !"consigliato".equals(targetNode.asText())) continue;

            playerRepository.findById(e.getKey())
                    .filter(p -> "Y".equals(p.getEnabled()))
                    .map(PlayerDocument::getDiscordName)
                    .ifPresent(discordNames::add);
        }
        return discordNames;
    }

    public boolean isValidApiKey(String providedKey) {
        return sysConfigRepository.getValue("EXTERNAL-API-KEY")
                .filter(expected -> expected.equals(providedKey))
                .isPresent();
    }

    @SuppressWarnings("unchecked")
    private int fetchCurrentSeason() {
        String guildApiKey = sysConfigRepository.getValue("API-KEY")
                .orElseThrow(() -> new IllegalStateException("API-KEY gilda non configurata"));

        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", guildApiKey);
        headers.set("accept", "application/json");
        ResponseEntity<Map> response = rt.exchange(
                tacticusBaseUrl + "/guildRaid",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Chiamata API Tacticus fallita");
        }
        Object season = response.getBody().get("season");
        return season instanceof Number ? ((Number) season).intValue() : 0;
    }
}
