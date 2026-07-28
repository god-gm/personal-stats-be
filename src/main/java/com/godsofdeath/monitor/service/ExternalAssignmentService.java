package com.godsofdeath.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.godsofdeath.monitor.document.AssignmentDocument;
import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.TargetAssigneeDTO;
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
 * Espone verso sistemi esterni la risoluzione "boss identifier → userId dei player assegnati"
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
 * <p>
 * Riusato anche lato admin (dashboard, {@code AdminStatsController}) per la modale
 * "chi ha Consigliato su questo target": in quel caso l'unitId arriva già esatto
 * dalla card (nessuna fuzzy-resolution necessaria) ed è disponibile anche la rarity
 * dell'encounter, che qui viene usata per disambiguare i casi in cui lo stesso boss
 * compare due volte nella season (una volta a livello Legendary, una a Mythic).
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

    public GenericResponseDTO<List<String>> getAssignedPlayerIds(String bossIdentifier) {
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

            List<String> playerIds = collectConsigliatoPlayerIds(root, targetKey);
            return GenericResponseDTO.ok("Assegnazioni recuperate", playerIds);
        } catch (Exception e) {
            return GenericResponseDTO.ko("Boss non riconosciuto in anagrafica");
        }
    }

    /**
     * Variante admin: stesso identifier→assignee lookup di {@link #getAssignedPlayerIds},
     * con in più la rarity esplicita per disambiguare i casi in cui lo stesso boss compare
     * due volte nella season (Legendary e Mythic). Restituisce i nomi (per la UI), non i
     * soli userId.
     */
    public GenericResponseDTO<List<TargetAssigneeDTO>> getConsigliatoPlayers(String unitId, String rarity) {
        if (unitId == null || unitId.isBlank()) {
            return GenericResponseDTO.ko("unitId mancante");
        }
        if (!"Legendary".equals(rarity) && !"Mythic".equals(rarity)) {
            return GenericResponseDTO.ko("rarity non valida (atteso Legendary o Mythic)");
        }

        String resolvedName = bossLookupRepository.findNameByUnitId(unitId)
                .or(() -> bossLookupRepository.findNameByUnitIdContains(unitId))
                .orElse(unitId);

        int season = fetchCurrentSeason();
        Optional<AssignmentDocument> assignmentDoc = assignmentRepository.findLatestBySeason(season);
        if (assignmentDoc.isEmpty()) {
            return GenericResponseDTO.ok("Nessun assignment salvato per la season corrente", List.of());
        }

        try {
            JsonNode root = objectMapper.readTree(assignmentDoc.get().getAssignmentData());
            JsonNode bossesNode = root.path("stats").path("bosses");
            if (!bossesNode.isArray()) {
                return GenericResponseDTO.ok("Nessuna assegnazione trovata per questo target", List.of());
            }

            String targetKey = resolveTargetKey(resolvedName, bossesNode, rarity);
            if (targetKey == null) {
                return GenericResponseDTO.ok("Nessuna assegnazione trovata per questo target", List.of());
            }

            List<TargetAssigneeDTO> players = collectConsigliatoPlayers(root, targetKey);
            return GenericResponseDTO.ok("Assegnazioni recuperate", players);
        } catch (Exception e) {
            return GenericResponseDTO.ok("Nessuna assegnazione trovata per questo target", List.of());
        }
    }

    private String resolveTargetKey(String resolvedName, JsonNode bossesNode) {
        return resolveTargetKey(resolvedName, bossesNode, null);
    }

    /**
     * Confronto "LIKE" bidirezionale (stesso pattern di AssignmentService.buildUnitNameToTypeMap)
     * tra il nome risolto e bossDesc/mini.name già salvati nell'assignment; vince il match più
     * lungo, per evitare ambiguità tra nomi che si contengono a vicenda. Quando rarityFilter è
     * valorizzata, scarta a monte i boss configurati all'altra rarity (levelDesc che inizia per
     * "M" → Mythic, altrimenti Legendary) — necessario perché lo stesso boss può comparire due
     * volte nella season (una volta Legendary, una Mythic) con lo stesso bossDesc.
     */
    private String resolveTargetKey(String resolvedName, JsonNode bossesNode, String rarityFilter) {
        String needle = resolvedName.toLowerCase();
        String bestKey = null;
        int    bestLength = -1;

        for (JsonNode b : bossesNode) {
            if (rarityFilter != null) {
                String levelDesc  = b.path("levelDesc").asText("");
                String bossRarity = levelDesc.startsWith("M") ? "Mythic" : "Legendary";
                if (!rarityFilter.equals(bossRarity)) continue;
            }

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

    private List<TargetAssigneeDTO> collectConsigliatoPlayers(JsonNode root, String targetKey) {
        JsonNode assignmentsNode = root.get("assignments");
        if (assignmentsNode == null || !assignmentsNode.isObject()) return List.of();

        List<TargetAssigneeDTO> players = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = assignmentsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode targetNode = e.getValue().get(targetKey);
            if (targetNode == null || !"consigliato".equals(targetNode.asText())) continue;

            playerRepository.findById(e.getKey())
                    .filter(p -> "Y".equals(p.getEnabled()))
                    .ifPresent(p -> players.add(TargetAssigneeDTO.builder()
                            .userId(p.getUserId())
                            .playerName(p.getUserGameName())
                            .build()));
        }
        players.sort(Comparator.comparing(TargetAssigneeDTO::getPlayerName, String.CASE_INSENSITIVE_ORDER));
        return players;
    }

    private List<String> collectConsigliatoPlayerIds(JsonNode root, String targetKey) {
        JsonNode assignmentsNode = root.get("assignments");
        if (assignmentsNode == null || !assignmentsNode.isObject()) return List.of();

        List<String> playerIds = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = assignmentsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode targetNode = e.getValue().get(targetKey);
            if (targetNode == null || !"consigliato".equals(targetNode.asText())) continue;

            String userId = e.getKey();
            playerRepository.findById(userId)
                    .filter(p -> "Y".equals(p.getEnabled()))
                    .map(PlayerDocument::getUserId)
                    .ifPresent(playerIds::add);
        }
        return playerIds;
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
