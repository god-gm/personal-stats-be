package com.godsofdeath.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.godsofdeath.monitor.document.AssignmentDocument;
import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.output.CurrentSeasonDataDTO;
import com.godsofdeath.monitor.dto.output.CurrentSeasonDataDTO.BossGroupDTO;
import com.godsofdeath.monitor.dto.output.CurrentSeasonDataDTO.EncounterDTO;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.repository.AssignmentRepository;
import com.godsofdeath.monitor.repository.BossLookupRepository;
import com.godsofdeath.monitor.repository.HiddenSideRepository;
import com.godsofdeath.monitor.repository.PlayerRepository;
import com.godsofdeath.monitor.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RaidService {

    private final PlayerRepository      playerRepository;
    private final SysConfigRepository   sysConfigRepository;
    private final BossLookupRepository  bossLookupRepository;
    private final AssignmentRepository  assignmentRepository;
    private final HiddenSideRepository  hiddenSideRepository;
    private final ObjectMapper          objectMapper;

    @Value("${tacticus.api.base-url}")
    private String tacticusBaseUrl;

    public GenericResponseDTO<CurrentSeasonDataDTO> getCurrentSeason(String currentUserId) {
        // --- Recupera API Key gilda ---
        String guildApiKey = sysConfigRepository.getValue("API-KEY")
                .orElseThrow(() -> new IllegalStateException("API-KEY gilda non configurata"));

        // --- Chiama API Tacticus ---
        Map<String, Object> apiData = callTacticusApi(guildApiKey);
        int season = ((Number) apiData.getOrDefault("season", 0)).intValue();
        List<Map<String, Object>> entries = (List<Map<String, Object>>) apiData.getOrDefault("entries", List.of());

        // --- Carica assignment (usato per gruppi scheletro E per i badge assegnazione) ---
        Optional<AssignmentDocument> assignmentDoc = assignmentRepository.findLatestBySeason(season);

        // --- Carica hidden sides per l'assignment corrente ---
        Set<String> hiddenEncounterKeys = buildHiddenEncounterKeys(assignmentDoc);

        // --- Giocatori abilitati ---
        Map<String, PlayerDocument> enabledPlayers = playerRepository.findAllEnabled()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));

        // --- Strutture di accumulo ---
        // groupKey (bossPrefix|rarity) es. "GuildBoss7|Legendary" → TypeGroup
        // bossPrefix estratto dallo unitId garantisce che boss e i suoi mini (che condividono
        // lo stesso prefisso GuildBossX) non vengano mai mescolati con altri encounter.
        Map<String, TypeGroup> typeGroups = new LinkedHashMap<>();
        Map<String, Integer> playerBombs  = new HashMap<>();
        Map<String, Integer> playerTokens = new HashMap<>();
        int[] legendaryCounter = {0};
        int[] mythicCounter    = {0};

        for (Map<String, Object> entry : entries) {
            String userId        = str(entry, "userId");
            String type          = str(entry, "type");
            String unitId        = str(entry, "unitId");
            String encounterType = str(entry, "encounterType");
            String damageType    = str(entry, "damageType");
            String rarity        = str(entry, "rarity");
            long   damageDealt   = toLong(entry, "damageDealt");
            long   remainingHp   = toLong(entry, "remainingHp");
            long   maxHp         = toLong(entry, "maxHp");

            // Bomb: traccia token solo per il giocatore corrente
            if ("Bomb".equals(damageType)) {
                if (userId.equals(currentUserId)) playerBombs.merge(userId, 1, Integer::sum);
                continue;
            }

            // Token: traccia solo per il giocatore corrente
            if (userId.equals(currentUserId)) playerTokens.merge(userId, 1, Integer::sum);

            // Solo Legendary e Mythic definiscono i gruppi di incontro
            if (!"Legendary".equals(rarity) && !"Mythic".equals(rarity)) continue;

            String bossPrefix = extractBossPrefix(unitId);   // es. "GuildBoss7"
            String bossTypeCapture = type;                    // per lambda (effectively final)
            String groupKey = bossPrefix + "|" + rarity;
            typeGroups.computeIfAbsent(groupKey, k -> {
                String lbl = "Legendary".equals(rarity)
                        ? "L" + (++legendaryCounter[0])
                        : "M" + (++mythicCounter[0]);
                return new TypeGroup(lbl, rarity, bossTypeCapture);
            });
            TypeGroup group = typeGroups.get(groupKey);

            group.units.computeIfAbsent(unitId, k -> new UnitStats(encounterType));

            // Mantieni ordine di prima comparsa
            if (!group.unitOrder.contains(unitId)) {
                group.unitOrder.add(unitId);
            }

            // Boss: killing blow = remainingHp == 0 → escludi
            // SideBoss: killing blow = remainingHp == 0 AND damageDealt != maxHp → escludi
            //           (solo kill su mini conta: remainingHp==0 ma damageDealt==maxHp)
            boolean isKillingBlow = "Boss".equals(encounterType)
                    ? remainingHp == 0
                    : "SideBoss".equals(encounterType) && remainingHp == 0 && damageDealt != maxHp;

            UnitStats unit = group.units.get(unitId);

            // Media gilda: tutti i giocatori dell'API response
            if (!isKillingBlow) {
                unit.guildDamageSum  += damageDealt;
                unit.guildAttackCount++;
            }

            // Stats individuali: solo il giocatore corrente
            if (userId.equals(currentUserId)) {
                unit.playerStats.computeIfAbsent(userId, k -> new PlayerUnitStats());
                unit.playerStats.get(userId).attackCount++;
                if (!isKillingBlow) {
                    unit.playerStats.get(userId).damageSum        += damageDealt;
                    unit.playerStats.get(userId).validAttackCount++;
                    if (damageDealt > unit.playerStats.get(userId).maxDamage) {
                        unit.playerStats.get(userId).maxDamage = damageDealt;
                    }
                }
            }
        }

        // --- Aggiunge gruppi scheletro per boss non ancora combattuti (inizio season) ---
        addSkeletonGroupsFromAssignment(assignmentDoc, typeGroups);

        // --- Assegnazioni per il player corrente ---
        Map<String, String> playerAssignments = loadPlayerAssignments(currentUserId, assignmentDoc);

        // --- Player corrente ---
        PlayerDocument currentPlayer = enabledPlayers.get(currentUserId);
        String playerName = currentPlayer != null ? currentPlayer.getUserGameName() : currentUserId;

        // --- Costruzione bossGroups ---
        // I gruppi scheletro (fromAssignment=true) passano sempre il filtro anche senza dati reali
        List<BossGroupDTO> bossGroups = typeGroups.entrySet().stream()
                .filter(e -> e.getValue().fromAssignment
                          || e.getValue().units.values().stream().anyMatch(u -> u.guildAttackCount > 0))
                .map(e -> buildBossGroup(e.getKey(), e.getValue(), currentUserId, playerAssignments, hiddenEncounterKeys))
                .filter(bg -> !bg.getEncounters().isEmpty())
                .collect(Collectors.toList());

        CurrentSeasonDataDTO data = CurrentSeasonDataDTO.builder()
                .season(season)
                .playerName(playerName)
                .totalTokensUsed(playerTokens.getOrDefault(currentUserId, 0))
                .totalBombsUsed(playerBombs.getOrDefault(currentUserId, 0))
                .bossGroups(bossGroups)
                .build();

        return GenericResponseDTO.ok("Stagione corrente recuperata", data);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private BossGroupDTO buildBossGroup(String groupKey, TypeGroup group, String currentUserId,
                                        Map<String, String> playerAssignments,
                                        Set<String> hiddenEncounterKeys) {
        // group.bossType = tipo boss dall'API (es. "RogalDorn"), usato per la chiave assignment
        String bossApiType = group.bossType;

        // Nome boss: primo unitId di tipo Boss nel gruppo, decodificato dalla lookup
        String bossName = group.unitOrder.stream()
                .filter(uid -> "Boss".equals(group.units.get(uid).encounterType))
                .findFirst()
                .map(uid -> resolveBossName(uid, false))
                .orElse(bossApiType);

        List<EncounterDTO> encounters = new ArrayList<>();

        for (String unitId : group.unitOrder) {
            UnitStats unit = group.units.get(unitId);
            if (unit == null) continue;

            double guildAverage = unit.guildAttackCount > 0
                    ? (double) unit.guildDamageSum / unit.guildAttackCount : 0.0;

            PlayerUnitStats ps = unit.playerStats.getOrDefault(currentUserId, new PlayerUnitStats());
            double playerAverage = ps.validAttackCount > 0
                    ? (double) ps.damageSum / ps.validAttackCount : 0.0;

            String indicator;
            if (ps.attackCount == 0) {
                indicator = "none";
            } else if (ps.validAttackCount == 0) {
                // all attacks were killing blows — no valid average to compare
                indicator = "none";
            } else if (guildAverage == 0) {
                indicator = "average";
            } else {
                double ratio = (playerAverage - guildAverage) / guildAverage;
                indicator = ratio > 0.10 ? "above" : ratio < -0.10 ? "below" : "average";
            }

            boolean isSide = !"Boss".equals(unit.encounterType);

            // Salta i mini marcati come hidden nell'assignment
            if (isSide) {
                String miniType = extractMiniTypeFromUnitId(unitId);
                if (hiddenEncounterKeys.contains(bossApiType + "__" + miniType)) continue;
            }

            String name = resolveBossName(unitId, isSide);

            String miniType = isSide ? extractMiniTypeFromUnitId(unitId) : null;
            String lookupKey = group.rarity + "|" + bossApiType + (miniType != null ? "__" + miniType : "");
            String assignmentType = playerAssignments.get(lookupKey);

            encounters.add(EncounterDTO.builder()
                    .unitId(unitId)
                    .name(name)
                    .encounterType(unit.encounterType)
                    .guildAverage(Math.round(guildAverage * 100.0) / 100.0)
                    .playerAverage(Math.round(playerAverage * 100.0) / 100.0)
                    .playerBest(ps.maxDamage)
                    .playerAttackCount(ps.attackCount)
                    .performanceIndicator(indicator)
                    .assignmentType(assignmentType)
                    .build());
        }

        return BossGroupDTO.builder()
                .label(group.label)
                .bossName(bossName)
                .encounters(encounters)
                .build();
    }

    /**
     * Loads player assignments and returns a map keyed by "rarity|apiType" (boss)
     * or "rarity|apiType__miniType" (mini). Rarity is derived from stats.bosses.levelDesc:
     * levelDesc starting with "M" → Mythic, otherwise → Legendary.
     * Supports both new-format keys ("levelId_apiType") and old-format ("apiType").
     */
    private Map<String, String> loadPlayerAssignments(String userId, Optional<AssignmentDocument> doc) {
        if (doc.isEmpty()) return Collections.emptyMap();
        try {
            JsonNode root = objectMapper.readTree(doc.get().getAssignmentData());

            Map<String, String> rawAssignments = new HashMap<>();
            JsonNode assignmentsNode = root.get("assignments");
            if (assignmentsNode != null) {
                JsonNode playerNode = assignmentsNode.get(userId);
                if (playerNode != null && playerNode.isObject()) {
                    playerNode.fields().forEachRemaining(e -> rawAssignments.put(e.getKey(), e.getValue().asText()));
                }
            }

            // Build rarity|apiType → levelId from stats.bosses (levelDesc "M*" = Mythic, else Legendary)
            Map<String, Integer> bossRarityToLevelId = new HashMap<>();
            JsonNode statsNode = root.get("stats");
            if (statsNode != null) {
                JsonNode bossesNode = statsNode.get("bosses");
                if (bossesNode != null && bossesNode.isArray()) {
                    for (JsonNode b : bossesNode) {
                        String apiType   = b.path("apiType").asText("");
                        String levelDesc = b.path("levelDesc").asText("");
                        int    levelId   = b.path("levelId").asInt();
                        String rarity    = levelDesc.startsWith("M") ? "Mythic" : "Legendary";
                        bossRarityToLevelId.put(rarity + "|" + apiType, levelId);
                    }
                }
            }

            // Build result: "rarity|apiType" → value, "rarity|apiType__miniType" → value
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, Integer> entry : bossRarityToLevelId.entrySet()) {
                String rarityApiType = entry.getKey();
                int    levelId       = entry.getValue();
                String apiType       = rarityApiType.substring(rarityApiType.indexOf('|') + 1);
                String newBossKey    = levelId + "_" + apiType;
                String oldBossKey    = apiType;

                String bossValue = rawAssignments.containsKey(newBossKey)
                        ? rawAssignments.get(newBossKey) : rawAssignments.get(oldBossKey);
                if (bossValue != null) result.put(rarityApiType, bossValue);

                for (Map.Entry<String, String> ae : rawAssignments.entrySet()) {
                    String k = ae.getKey();
                    String miniPart = null;
                    if (k.startsWith(newBossKey + "__")) {
                        miniPart = k.substring(newBossKey.length() + 2);
                    } else if (k.startsWith(oldBossKey + "__") && !k.matches("^\\d+_.*")) {
                        miniPart = k.substring(oldBossKey.length() + 2);
                    }
                    if (miniPart != null) result.put(rarityApiType + "__" + miniPart, ae.getValue());
                }
            }

            return result;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Per ogni boss presente nell'assignment salvato ma assente in typeGroups,
     * aggiunge un TypeGroup scheletro (0 attacchi) così la card compare anche
     * a inizio season quando l'API non ha ancora dati di combattimento.
     */
    private void addSkeletonGroupsFromAssignment(Optional<AssignmentDocument> doc,
                                                  Map<String, TypeGroup> typeGroups) {
        if (doc.isEmpty()) return;
        try {
            JsonNode root      = objectMapper.readTree(doc.get().getAssignmentData());
            JsonNode statsNode = root.get("stats");
            if (statsNode == null) return;
            JsonNode bossesNode = statsNode.get("bosses");
            if (bossesNode == null || !bossesNode.isArray()) return;

            for (JsonNode b : bossesNode) {
                String apiType   = b.path("apiType").asText("");
                String levelDesc = b.path("levelDesc").asText("L?");
                if (apiType.isEmpty()) continue;

                String rarity = levelDesc.startsWith("M") ? "Mythic" : "Legendary";

                JsonNode minisNode = b.get("minis");

                // Cerca un gruppo reale già esistente per questo bossType + rarity
                TypeGroup existing = typeGroups.values().stream()
                        .filter(g -> g.bossType.equals(apiType) && g.rarity.equals(rarity))
                        .findFirst().orElse(null);

                if (existing != null) {
                    // Gruppo reale presente: controlla se mancano mini (0 attacchi nell'API)
                    if (minisNode != null && minisNode.isArray()) {
                        for (JsonNode m : minisNode) {
                            String miniUnitId = m.path("unitId").asText("");
                            if (miniUnitId.isEmpty()) continue;
                            boolean miniPresent = existing.unitOrder.stream()
                                    .anyMatch(uid -> extractMiniTypeFromUnitId(uid).equals(miniUnitId));
                            if (!miniPresent) {
                                existing.unitOrder.add(miniUnitId);
                                existing.units.put(miniUnitId, new UnitStats("SideBoss"));
                            }
                        }
                    }
                    continue;
                }

                // Gruppo mancante del tutto: crea skeleton completo
                TypeGroup skeleton = new TypeGroup(levelDesc, rarity, apiType);
                skeleton.fromAssignment = true;

                skeleton.unitOrder.add(apiType);
                skeleton.units.put(apiType, new UnitStats("Boss"));

                if (minisNode != null && minisNode.isArray()) {
                    for (JsonNode m : minisNode) {
                        String miniUnitId = m.path("unitId").asText("");
                        if (miniUnitId.isEmpty()) continue;
                        skeleton.unitOrder.add(miniUnitId);
                        skeleton.units.put(miniUnitId, new UnitStats("SideBoss"));
                    }
                }

                typeGroups.put(apiType + "|" + rarity, skeleton);
            }
        } catch (Exception ignored) {
            // Best-effort: se il JSON è malformato si mostra solo ciò che arriva dall'API
        }
    }

    /**
     * Builds a Set of "apiType__miniType" keys for sides hidden in the latest assignment.
     * Each hidden_side key has format "levelId_apiType__miniUnitId"; we strip the "levelId_" prefix.
     */
    private Set<String> buildHiddenEncounterKeys(Optional<AssignmentDocument> assignmentDoc) {
        if (assignmentDoc.isEmpty()) return Collections.emptySet();
        try {
            List<String> sideKeys = hiddenSideRepository.findByAssignmentName(assignmentDoc.get().getName());
            Set<String> result = new HashSet<>();
            for (String key : sideKeys) {
                int firstUnderscore = key.indexOf('_');
                if (firstUnderscore < 0) continue;
                // Strip leading "levelId_" to get "apiType__miniUnitId"
                result.add(key.substring(firstUnderscore + 1));
            }
            return result;
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }

    private String extractMiniTypeFromUnitId(String unitId) {
        if (unitId.contains("MiniBoss")) {
            int idx = unitId.indexOf("MiniBoss");
            String rest = unitId.substring(idx + "MiniBoss".length());
            if (!rest.isEmpty() && Character.isDigit(rest.charAt(0))) {
                rest = rest.substring(1);
            }
            return rest;
        }
        return unitId;
    }

    /**
     * Estrae il prefisso encounter da uno unitId.
     * Es. "GuildBoss7Boss1AstraRogaldorn"     → "GuildBoss7"
     *     "GuildBoss7MiniBoss1AstraPrimarisPsy" → "GuildBoss7"
     * Boss e mini dello stesso encounter condividono questo prefisso.
     */
    private String extractBossPrefix(String unitId) {
        if (unitId.startsWith("GuildBoss")) {
            int i = "GuildBoss".length();
            while (i < unitId.length() && Character.isDigit(unitId.charAt(i))) i++;
            return unitId.substring(0, i);
        }
        return unitId;
    }

    private String resolveBossName(String unitId, boolean sideMode) {
        if (!sideMode) {
            Optional<String> exact = bossLookupRepository.findNameByUnitId(unitId);
            if (exact.isPresent()) return exact.get();
        }
        return bossLookupRepository.findNameByUnitIdContains(unitId).orElse(unitId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTacticusApi(String apiKey) {
        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.set("accept", "application/json");
        ResponseEntity<Map> response = rt.exchange(
                tacticusBaseUrl + "/guildRaid",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Chiamata API Tacticus fallita");
        }
        return response.getBody();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : "";
    }

    private static long toLong(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static int toInt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    // ----------------------------------------------------------------
    // Inner accumulator classes
    // ----------------------------------------------------------------

    private static class TypeGroup {
        String label;          // "L1", "M2", ecc.
        String rarity;         // "Legendary" o "Mythic"
        String bossType;       // tipo boss dall'API, es. "RogalDorn"
        boolean fromAssignment; // gruppo scheletro da assignment (nessun dato reale)
        List<String> unitOrder = new ArrayList<>();
        Map<String, UnitStats> units = new LinkedHashMap<>();

        TypeGroup(String label, String rarity, String bossType) {
            this.label    = label;
            this.rarity   = rarity;
            this.bossType = bossType;
        }
    }

    private static class UnitStats {
        String encounterType;
        long guildDamageSum;
        int  guildAttackCount;
        Map<String, PlayerUnitStats> playerStats = new HashMap<>();

        UnitStats(String encounterType) { this.encounterType = encounterType; }
    }

    private static class PlayerUnitStats {
        long damageSum;
        long maxDamage;
        int  attackCount;
        int  validAttackCount;
    }
}
