package com.godsofdeath.monitor.service;

import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.output.CurrentSeasonDataDTO;
import com.godsofdeath.monitor.dto.output.CurrentSeasonDataDTO.BossGroupDTO;
import com.godsofdeath.monitor.dto.output.CurrentSeasonDataDTO.EncounterDTO;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
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

        // --- Giocatori abilitati ---
        Map<String, PlayerDocument> enabledPlayers = playerRepository.findAllEnabled()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));

        // --- Strutture di accumulo ---
        // groupKey (type|rarity) → { label, rarity, unitOrder, units }
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
            int    set           = toInt(entry, "set");

            if (!enabledPlayers.containsKey(userId)) continue;

            // Bomb
            if ("Bomb".equals(damageType)) {
                playerBombs.merge(userId, 1, Integer::sum);
                continue;
            }

            // Token
            playerTokens.merge(userId, 1, Integer::sum);

            // Solo Legendary e Mythic definiscono i gruppi di incontro
            if (!"Legendary".equals(rarity) && !"Mythic".equals(rarity)) continue;

            String groupKey = type + "|" + rarity;
            typeGroups.computeIfAbsent(groupKey, k -> {
                String lbl = "Legendary".equals(rarity)
                        ? "L" + (++legendaryCounter[0])
                        : "M" + (++mythicCounter[0]);
                return new TypeGroup(lbl, rarity);
            });
            TypeGroup group = typeGroups.get(groupKey);

            group.units.computeIfAbsent(unitId, k -> new UnitStats(encounterType));

            // Mantieni ordine di prima comparsa
            if (!group.unitOrder.contains(unitId)) {
                group.unitOrder.add(unitId);
            }

            boolean isKillingBlow = (remainingHp == 0 && "Boss".equals(encounterType));

            UnitStats unit = group.units.get(unitId);
            unit.playerStats.computeIfAbsent(userId, k -> new PlayerUnitStats());
            unit.playerStats.get(userId).attackCount++;

            if (!isKillingBlow) {
                unit.playerStats.get(userId).damageSum        += damageDealt;
                unit.playerStats.get(userId).validAttackCount++;
                unit.guildDamageSum  += damageDealt;
                unit.guildAttackCount++;
            }
        }

        // --- Player corrente ---
        PlayerDocument currentPlayer = enabledPlayers.get(currentUserId);
        String playerName = currentPlayer != null ? currentPlayer.getUserGameName() : currentUserId;

        // --- Costruzione bossGroups ---
        // LinkedHashMap preserva l'ordine di inserimento (= ordine di comparsa nel report)
        List<BossGroupDTO> bossGroups = typeGroups.entrySet().stream()
                .filter(e -> e.getValue().units.values().stream().anyMatch(u -> u.guildAttackCount > 0))
                .map(e -> buildBossGroup(e.getKey(), e.getValue(), currentUserId))
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

    private BossGroupDTO buildBossGroup(String groupKey, TypeGroup group, String currentUserId) {
        // Nome boss: primo unitId di tipo Boss nel gruppo, decodificato dalla lookup
        String bossName = group.unitOrder.stream()
                .filter(uid -> "Boss".equals(group.units.get(uid).encounterType))
                .findFirst()
                .map(uid -> resolveBossName(uid, false))
                .orElseGet(() -> groupKey.split("\\|")[0]);

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

            encounters.add(EncounterDTO.builder()
                    .unitId(unitId)
                    .name(name)
                    .encounterType(unit.encounterType)
                    .guildAverage(Math.round(guildAverage * 100.0) / 100.0)
                    .playerAverage(Math.round(playerAverage * 100.0) / 100.0)
                    .playerAttackCount(ps.attackCount)
                    .performanceIndicator(indicator)
                    .build());
        }

        return BossGroupDTO.builder()
                .label(group.label)
                .bossName(bossName)
                .encounters(encounters)
                .build();
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
        String label;    // "L1", "M2", ecc.
        String rarity;   // "Legendary" o "Mythic"
        List<String> unitOrder = new ArrayList<>();
        Map<String, UnitStats> units = new LinkedHashMap<>();

        TypeGroup(String label, String rarity) {
            this.label  = label;
            this.rarity = rarity;
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
