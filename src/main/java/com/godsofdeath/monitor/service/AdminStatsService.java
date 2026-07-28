package com.godsofdeath.monitor.service;

import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.GuildStatEntryDTO;
import com.godsofdeath.monitor.dto.output.TargetAttackStatDTO;
import com.godsofdeath.monitor.dto.output.TokenUsageEntryDTO;
import com.godsofdeath.monitor.repository.PlayerRepository;
import com.godsofdeath.monitor.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Statistiche admin-only sull'intera gilda per la season corrente: token usati
 * (tutti i boss, no bombe) e media danno overall (solo Legendary/Mythic, no bombe,
 * killing blow esclusi). Endpoint dedicati, indipendenti da RaidService/AssignmentService.
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final PlayerRepository    playerRepository;
    private final SysConfigRepository sysConfigRepository;

    @Value("${tacticus.api.base-url}")
    private String tacticusBaseUrl;

    public GenericResponseDTO<List<TokenUsageEntryDTO>> getTokenUsage() {
        List<Map<String, Object>> entries = fetchCurrentSeasonEntries();
        Map<String, PlayerDocument> enabledPlayers = playerRepository.findAllEnabled()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));

        Map<String, Integer> tokenByPlayer = new HashMap<>();
        for (Map<String, Object> entry : entries) {
            String userId     = str(entry, "userId");
            String damageType = str(entry, "damageType");
            if ("Bomb".equals(damageType)) continue;
            if (!enabledPlayers.containsKey(userId)) continue;
            tokenByPlayer.merge(userId, 1, Integer::sum);
        }

        List<TokenUsageEntryDTO> result = enabledPlayers.values().stream()
                .map(p -> TokenUsageEntryDTO.builder()
                        .playerName(p.getUserGameName())
                        .discordName(p.getDiscordName())
                        .tokenCount(tokenByPlayer.getOrDefault(p.getUserId(), 0))
                        .build())
                .sorted(Comparator
                        .comparingInt(TokenUsageEntryDTO::getTokenCount).reversed()
                        .thenComparing(TokenUsageEntryDTO::getPlayerName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        return GenericResponseDTO.ok("Token usage recuperato", result);
    }

    public GenericResponseDTO<List<GuildStatEntryDTO>> getGuildStats() {
        List<Map<String, Object>> entries = fetchCurrentSeasonEntries();
        Map<String, PlayerDocument> enabledPlayers = playerRepository.findAllEnabled()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));

        Map<String, Long> damageSumByPlayer  = new HashMap<>();
        Map<String, Integer> attackCountByPlayer = new HashMap<>();

        for (Map<String, Object> entry : entries) {
            String userId       = str(entry, "userId");
            String rarity       = str(entry, "rarity");
            String damageType   = str(entry, "damageType");
            String encounterType = str(entry, "encounterType");
            long   damageDealt  = toLong(entry, "damageDealt");
            long   remainingHp  = toLong(entry, "remainingHp");
            long   maxHp        = toLong(entry, "maxHp");

            if (!enabledPlayers.containsKey(userId)) continue;
            if (!"Legendary".equals(rarity) && !"Mythic".equals(rarity)) continue;
            if (!"Battle".equals(damageType)) continue;

            boolean isKillingBlow = "Boss".equals(encounterType)
                    ? remainingHp == 0
                    : "SideBoss".equals(encounterType) && remainingHp == 0 && damageDealt != maxHp;
            if (isKillingBlow) continue;

            damageSumByPlayer.merge(userId, damageDealt, Long::sum);
            attackCountByPlayer.merge(userId, 1, Integer::sum);
        }

        List<GuildStatEntryDTO> result = enabledPlayers.values().stream()
                .map(p -> {
                    int attackCount = attackCountByPlayer.getOrDefault(p.getUserId(), 0);
                    long damageSum  = damageSumByPlayer.getOrDefault(p.getUserId(), 0L);
                    double average  = attackCount > 0 ? (double) damageSum / attackCount : 0;
                    return GuildStatEntryDTO.builder()
                            .playerName(p.getUserGameName())
                            .discordName(p.getDiscordName())
                            .average(Math.round(average * 100.0) / 100.0)
                            .attackCount(attackCount)
                            .build();
                })
                .sorted(Comparator.comparingDouble(GuildStatEntryDTO::getAverage).reversed())
                .collect(Collectors.toList());

        return GenericResponseDTO.ok("Guild stats recuperate", result);
    }

    /**
     * Player che hanno attaccato un target specifico (unitId + rarity) nella season corrente,
     * con conteggio attacchi e media danno. La rarity è necessaria perché lo stesso unitId può
     * comparire nella season sia a livello Legendary che Mythic (boss/mini fought at multiple
     * tiers) — senza il filtro rarity le due occorrenze verrebbero sommate insieme.
     */
    public GenericResponseDTO<List<TargetAttackStatDTO>> getTargetAttackStats(String unitId, String rarity) {
        if (unitId == null || unitId.isBlank()) {
            return GenericResponseDTO.ko("unitId mancante");
        }
        if (!"Legendary".equals(rarity) && !"Mythic".equals(rarity)) {
            return GenericResponseDTO.ko("rarity non valida (atteso Legendary o Mythic)");
        }

        List<Map<String, Object>> entries = fetchCurrentSeasonEntries();
        Map<String, PlayerDocument> enabledPlayers = playerRepository.findAllEnabled()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));

        Map<String, Long>    damageSumByPlayer  = new HashMap<>();
        Map<String, Integer> validCountByPlayer = new HashMap<>();
        Map<String, Integer> attackCountByPlayer = new HashMap<>();

        for (Map<String, Object> entry : entries) {
            if (!unitId.equals(str(entry, "unitId"))) continue;
            if (!rarity.equals(str(entry, "rarity"))) continue;
            if (!"Battle".equals(str(entry, "damageType"))) continue;

            String userId = str(entry, "userId");
            if (!enabledPlayers.containsKey(userId)) continue;

            String encounterType = str(entry, "encounterType");
            long   damageDealt   = toLong(entry, "damageDealt");
            long   remainingHp   = toLong(entry, "remainingHp");
            long   maxHp         = toLong(entry, "maxHp");

            attackCountByPlayer.merge(userId, 1, Integer::sum);

            boolean isKillingBlow = "Boss".equals(encounterType)
                    ? remainingHp == 0
                    : "SideBoss".equals(encounterType) && remainingHp == 0 && damageDealt != maxHp;
            if (!isKillingBlow) {
                damageSumByPlayer.merge(userId, damageDealt, Long::sum);
                validCountByPlayer.merge(userId, 1, Integer::sum);
            }
        }

        List<TargetAttackStatDTO> result = attackCountByPlayer.entrySet().stream()
                .map(e -> {
                    String userId     = e.getKey();
                    int    validCount = validCountByPlayer.getOrDefault(userId, 0);
                    long   damageSum  = damageSumByPlayer.getOrDefault(userId, 0L);
                    double average    = validCount > 0 ? (double) damageSum / validCount : 0;
                    return TargetAttackStatDTO.builder()
                            .userId(userId)
                            .playerName(enabledPlayers.get(userId).getUserGameName())
                            .attackCount(e.getValue())
                            .average(Math.round(average * 100.0) / 100.0)
                            .build();
                })
                .sorted(Comparator.comparingDouble(TargetAttackStatDTO::getAverage).reversed())
                .collect(Collectors.toList());

        return GenericResponseDTO.ok("Statistiche attacchi recuperate", result);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchCurrentSeasonEntries() {
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
        Object entries = response.getBody().get("entries");
        return entries instanceof List ? (List<Map<String, Object>>) entries : Collections.emptyList();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : "";
    }

    private static long toLong(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }
}
