package com.godsofdeath.monitor.service;

import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.output.*;
import com.godsofdeath.monitor.repository.BossLookupRepository;
import com.godsofdeath.monitor.repository.PlayerRepository;
import com.godsofdeath.monitor.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeasonRankingService {

    private final PlayerRepository     playerRepository;
    private final SysConfigRepository  sysConfigRepository;
    private final BossLookupRepository bossLookupRepository;

    @Value("${tacticus.api.base-url}")
    private String tacticusBaseUrl;

    /**
     * Classifica gilda per la season selezionata.
     * Ogni giocatore accumula delta = (danno_attacco - media_gilda_target) per ogni attacco valido:
     * Battle only, Legendary/Mythic only, killing blow esclusi per le stesse regole del dashboard.
     */
    public GenericResponseDTO<SeasonRankingDTO> getSeasonRanking(Integer seasonNumber) {
        String guildApiKey = sysConfigRepository.getValue("API-KEY")
                .orElseThrow(() -> new IllegalStateException("API-KEY gilda non configurata"));

        Map<String, Object> currentData = callApi(guildApiKey, null);
        int currentSeason = ((Number) currentData.getOrDefault("season", 0)).intValue();

        List<Integer> availableSeasons = List.of(currentSeason, currentSeason - 1, currentSeason - 2);

        int selectedSeason = (seasonNumber != null) ? seasonNumber : currentSeason;
        Map<String, Object> seasonData = (selectedSeason == currentSeason)
                ? currentData : callApi(guildApiKey, selectedSeason);

        Map<String, PlayerDocument> enabledPlayers = playerRepository.findAllEnabled()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));

        List<Map<String, Object>> entries = getEntries(seasonData);

        // Pass 1: guild average per target key (unitId|rarity), excluding killing blows
        Map<String, Long>    guildDamageSum   = new HashMap<>();
        Map<String, Integer> guildAttackCount = new HashMap<>();

        for (Map<String, Object> e : entries) {
            if (!"Battle".equals(str(e, "damageType"))) continue;
            String rarity = str(e, "rarity");
            if (!"Legendary".equals(rarity) && !"Mythic".equals(rarity)) continue;
            if (!enabledPlayers.containsKey(str(e, "userId"))) continue;

            String unitId        = str(e, "unitId");
            String encounterType = str(e, "encounterType");
            long   damageDealt   = toLong(e, "damageDealt");
            long   remainingHp   = toLong(e, "remainingHp");
            long   maxHp         = toLong(e, "maxHp");
            if (isKillingBlow(encounterType, remainingHp, maxHp, damageDealt)) continue;

            String key = unitId + "|" + rarity;
            guildDamageSum.merge(key, damageDealt, Long::sum);
            guildAttackCount.merge(key, 1, Integer::sum);
        }

        Map<String, Double> guildAvg = new HashMap<>();
        guildDamageSum.forEach((k, v) -> {
            int cnt = guildAttackCount.getOrDefault(k, 0);
            guildAvg.put(k, cnt > 0 ? (double) v / cnt : 0.0);
        });

        // Pass 2: per-player per-target valid damage accumulation.
        // Composite key: userId + "|||" + unitId + "|" + rarity  (triple-pipe avoids clash with unitId)
        Map<String, Long>    playerTargetDmgSum    = new HashMap<>();
        Map<String, Integer> playerTargetValidCount = new HashMap<>();

        for (Map<String, Object> e : entries) {
            if (!"Battle".equals(str(e, "damageType"))) continue;
            String rarity = str(e, "rarity");
            if (!"Legendary".equals(rarity) && !"Mythic".equals(rarity)) continue;
            String userId = str(e, "userId");
            if (!enabledPlayers.containsKey(userId)) continue;

            String unitId        = str(e, "unitId");
            String encounterType = str(e, "encounterType");
            long   damageDealt   = toLong(e, "damageDealt");
            long   remainingHp   = toLong(e, "remainingHp");
            long   maxHp         = toLong(e, "maxHp");
            if (isKillingBlow(encounterType, remainingHp, maxHp, damageDealt)) continue;

            String compositeKey = userId + "|||" + unitId + "|" + rarity;
            playerTargetDmgSum.merge(compositeKey, damageDealt, Long::sum);
            playerTargetValidCount.merge(compositeKey, 1, Integer::sum);
        }

        // Pass 3: weighted %PCI per player.
        // %PCI_total = Σ(PCI_target × validCount_target) / Σ(validCount_target)  [× 100 → %]
        // where PCI_target = (playerAvg_target - guildAvg_target) / guildAvg_target
        // Targets with guildAvg == 0 are skipped (no guild data → can't normalise).
        Map<String, Double>  pciNumerator  = new HashMap<>();  // Σ(PCI_target × validCount)
        Map<String, Integer> totalValid    = new HashMap<>();  // Σ(validCount)

        for (Map.Entry<String, Integer> entry : playerTargetValidCount.entrySet()) {
            String compositeKey   = entry.getKey();
            int    sep            = compositeKey.indexOf("|||");
            String userId         = compositeKey.substring(0, sep);
            String targetKey      = compositeKey.substring(sep + 3);

            double targetGuildAvg = guildAvg.getOrDefault(targetKey, 0.0);
            if (targetGuildAvg == 0.0) continue;

            int    validCount = entry.getValue();
            long   dmgSum     = playerTargetDmgSum.getOrDefault(compositeKey, 0L);
            double playerAvg  = (double) dmgSum / validCount;
            double pciTarget  = (playerAvg - targetGuildAvg) / targetGuildAvg;

            pciNumerator.merge(userId, pciTarget * validCount, Double::sum);
            totalValid.merge(userId, validCount, Integer::sum);
        }

        List<PlayerRankDTO> ranking = enabledPlayers.values().stream()
                .map(p -> {
                    String uid        = p.getUserId();
                    int    tv         = totalValid.getOrDefault(uid, 0);
                    double pciPercent = tv > 0 ? (pciNumerator.getOrDefault(uid, 0.0) / tv) * 100.0 : 0.0;
                    return PlayerRankDTO.builder()
                            .userId(uid)
                            .playerName(p.getUserGameName())
                            .pciPercent(Math.round(pciPercent * 100.0) / 100.0)
                            .validAttackCount(tv)
                            .build();
                })
                .sorted(Comparator.comparingDouble(PlayerRankDTO::getPciPercent).reversed())
                .collect(Collectors.toList());

        return GenericResponseDTO.ok("Classifica recuperata", SeasonRankingDTO.builder()
                .season(selectedSeason)
                .availableSeasons(availableSeasons)
                .ranking(ranking)
                .build());
    }

    /**
     * Storico incontri di un giocatore per una season.
     * Restituisce tutti gli attacchi Battle (no bombe), senza altre esclusioni,
     * marcando ciascuno se escluso dalla classifica (killing blow o rarity < Legendary).
     */
    public GenericResponseDTO<SeasonPlayerDetailDTO> getPlayerDetail(int seasonNumber, String userId) {
        String guildApiKey = sysConfigRepository.getValue("API-KEY")
                .orElseThrow(() -> new IllegalStateException("API-KEY gilda non configurata"));

        Map<String, Object> currentData = callApi(guildApiKey, null);
        int currentSeason = ((Number) currentData.getOrDefault("season", 0)).intValue();

        Map<String, Object> seasonData = (seasonNumber == currentSeason)
                ? currentData : callApi(guildApiKey, seasonNumber);

        Map<String, PlayerDocument> enabledPlayers = playerRepository.findAllEnabled()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));

        PlayerDocument player = enabledPlayers.get(userId);
        String playerName = player != null ? player.getUserGameName() : userId;

        // Pass 1: guild average per target (unitId|rarity), same rules as ranking
        List<Map<String, Object>> entries = getEntries(seasonData);
        Map<String, Long>    guildDmgSum   = new HashMap<>();
        Map<String, Integer> guildAtkCount = new HashMap<>();

        for (Map<String, Object> e : entries) {
            if (!"Battle".equals(str(e, "damageType"))) continue;
            String rarity = str(e, "rarity");
            if (!"Legendary".equals(rarity) && !"Mythic".equals(rarity)) continue;
            if (!enabledPlayers.containsKey(str(e, "userId"))) continue;

            String unitId        = str(e, "unitId");
            String encounterType = str(e, "encounterType");
            long   damageDealt   = toLong(e, "damageDealt");
            long   remainingHp   = toLong(e, "remainingHp");
            long   maxHp         = toLong(e, "maxHp");
            if (isKillingBlow(encounterType, remainingHp, maxHp, damageDealt)) continue;

            String key = unitId + "|" + rarity;
            guildDmgSum.merge(key, damageDealt, Long::sum);
            guildAtkCount.merge(key, 1, Integer::sum);
        }

        // Pass 2: player encounters (all non-bomb Battle attacks)
        List<PlayerEncounterDetailDTO> encounters = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (!userId.equals(str(e, "userId"))) continue;
            if ("Bomb".equals(str(e, "damageType"))) continue;

            String unitId        = str(e, "unitId");
            String encounterType = str(e, "encounterType");
            String rarity        = str(e, "rarity");
            long   damageDealt   = toLong(e, "damageDealt");
            long   remainingHp   = toLong(e, "remainingHp");
            long   maxHp         = toLong(e, "maxHp");

            boolean isKB     = isKillingBlow(encounterType, remainingHp, maxHp, damageDealt);
            boolean excluded = isKB || (!"Legendary".equals(rarity) && !"Mythic".equals(rarity));
            String  name     = resolveName(unitId, !"Boss".equals(encounterType));

            String key        = unitId + "|" + rarity;
            int    guildCnt   = guildAtkCount.getOrDefault(key, 0);
            double guildAvg   = guildCnt > 0
                    ? Math.round((double) guildDmgSum.getOrDefault(key, 0L) / guildCnt * 100.0) / 100.0
                    : 0.0;

            encounters.add(PlayerEncounterDetailDTO.builder()
                    .unitId(unitId)
                    .name(name)
                    .encounterType(encounterType)
                    .rarity(rarity)
                    .damageDealt(damageDealt)
                    .maxHp(maxHp)
                    .remainingHp(remainingHp)
                    .killingBlow(isKB)
                    .excludedFromRanking(excluded)
                    .guildAverage(guildAvg)
                    .heroes(parseHeroes(e.get("heroDetails")))
                    .machineOfWar(parseMow(e.get("machineOfWarDetails")))
                    .build());
        }

        return GenericResponseDTO.ok("Dettaglio recuperato", SeasonPlayerDetailDTO.builder()
                .playerName(playerName)
                .encounters(encounters)
                .build());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isKillingBlow(String encounterType, long remainingHp, long maxHp, long damageDealt) {
        if ("Boss".equals(encounterType))     return remainingHp == 0;
        if ("SideBoss".equals(encounterType)) return remainingHp == 0 && damageDealt != maxHp;
        return false;
    }

    private String resolveName(String unitId, boolean sideMode) {
        if (!sideMode) {
            Optional<String> exact = bossLookupRepository.findNameByUnitId(unitId);
            if (exact.isPresent()) return exact.get();
        }
        return bossLookupRepository.findNameByUnitIdContains(unitId).orElse(unitId);
    }

    @SuppressWarnings("unchecked")
    private List<HeroDetailDTO> parseHeroes(Object raw) {
        List<HeroDetailDTO> result = new ArrayList<>();
        if (!(raw instanceof List)) return result;
        for (Object h : (List<?>) raw) {
            if (!(h instanceof Map)) continue;
            Map<String, Object> hm = (Map<String, Object>) h;
            String uid = str(hm, "unitId");
            if (!uid.isEmpty()) result.add(HeroDetailDTO.builder().unitId(uid).power(toInt(hm, "power")).build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private HeroDetailDTO parseMow(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) raw;
        String uid = str(m, "unitId");
        return uid.isEmpty() ? null : HeroDetailDTO.builder().unitId(uid).power(toInt(m, "power")).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callApi(String apiKey, Integer seasonOverride) {
        RestTemplate rt = new RestTemplate();
        HttpHeaders  headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.set("accept", "application/json");
        String url = seasonOverride == null
                ? tacticusBaseUrl + "/guildRaid"
                : tacticusBaseUrl + "/guildRaid/" + seasonOverride;
        ResponseEntity<Map> response = rt.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Chiamata API Tacticus fallita: " + url);
        }
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getEntries(Map<String, Object> data) {
        Object e = data.get("entries");
        return e instanceof List ? (List<Map<String, Object>>) e : Collections.emptyList();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k); return v != null ? v.toString() : "";
    }
    private static long toLong(Map<String, Object> m, String k) {
        Object v = m.get(k); return v instanceof Number ? ((Number) v).longValue() : 0L;
    }
    private static int toInt(Map<String, Object> m, String k) {
        Object v = m.get(k); return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
