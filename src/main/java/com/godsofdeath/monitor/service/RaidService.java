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

        // --- Assegnazioni più recenti per questa season ---
        Map<String, String> playerAssignments = loadPlayerAssignments(currentUserId, season);

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
                }
            }
        }

        // --- Player corrente ---
        PlayerDocument currentPlayer = enabledPlayers.get(currentUserId);
        String playerName = currentPlayer != null ? currentPlayer.getUserGameName() : currentUserId;

        // --- Costruzione bossGroups ---
        // LinkedHashMap preserva l'ordine di inserimento (= ordine di comparsa nel report)
        List<BossGroupDTO> bossGroups = typeGroups.entrySet().stream()
                .filter(e -> e.getValue().units.values().stream().anyMatch(u -> u.guildAttackCount > 0))
                .map(e -> buildBossGroup(e.getKey(), e.getValue(), currentUserId, playerAssignments))
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
                                        Map<String, String> playerAssignments) {
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
            } else if (guildAverage == 0) {
                indicator = "average";
            } else {
                double ratio = (playerAverage - guildAverage) / guildAverage;
                indicator = ratio > 0.10 ? "above" : ratio < -0.10 ? "below" : "average";
            }

            boolean isSide = !"Boss".equals(unit.encounterType);
            String name = resolveBossName(unitId, isSide);

            String assignmentKey = isSide
                    ? bossApiType + "__" + extractMiniTypeFromUnitId(unitId)
                    : bossApiType;
            String assignmentType = playerAssignments.get(assignmentKey);

            encounters.add(EncounterDTO.builder()
                    .unitId(unitId)
                    .name(name)
                    .encounterType(unit.encounterType)
                    .guildAverage(Math.round(guildAverage * 100.0) / 100.0)
                    .playerAverage(Math.round(playerAverage * 100.0) / 100.0)
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

    private Map<String, String> loadPlayerAssignments(String userId, int seasonNumber) {
        Optional<AssignmentDocument> doc = assignmentRepository.findLatestBySeason(seasonNumber);
        if (doc.isEmpty()) return Collections.emptyMap();
        try {
            JsonNode root = objectMapper.readTree(doc.get().getAssignmentData());
            JsonNode assignmentsNode = root.get("assignments");
            if (assignmentsNode == null) return Collections.emptyMap();
            JsonNode playerNode = assignmentsNode.get(userId);
            if (playerNode == null || !playerNode.isObject()) return Collections.emptyMap();
            Map<String, String> result = new HashMap<>();
            playerNode.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
            return result;
        } catch (Exception e) {
            return Collections.emptyMap();
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
        String label;     // "L1", "M2", ecc.
        String rarity;    // "Legendary" o "Mythic"
        String bossType;  // tipo boss dall'API, es. "RogalDorn" (per chiave assignment e display)
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
        int  attackCount;
        int  validAttackCount;
    }
}
