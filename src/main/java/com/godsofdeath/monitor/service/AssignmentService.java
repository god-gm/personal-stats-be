package com.godsofdeath.monitor.service;

import com.godsofdeath.monitor.document.AnagBossDocument;
import com.godsofdeath.monitor.document.AssignmentDocument;
import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.input.AssignmentConfigInputDTO;
import com.godsofdeath.monitor.dto.input.SaveAssignmentInputDTO;
import com.godsofdeath.monitor.dto.output.*;
import com.godsofdeath.monitor.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssignmentService {

    private final AnagBossRepository    anagBossRepository;
    private final AnagLevelRepository   anagLevelRepository;
    private final BossLookupRepository  bossLookupRepository;
    private final PlayerRepository      playerRepository;
    private final SysConfigRepository   sysConfigRepository;
    private final AssignmentRepository  assignmentRepository;

    @Value("${tacticus.api.base-url}")
    private String tacticusBaseUrl;

    // ── Rarity ordering (higher = better) ──────────────────────────────────
    private static final Map<String, Integer> RARITY_ORDER;
    static {
        RARITY_ORDER = new HashMap<>();
        RARITY_ORDER.put("Common",    0);
        RARITY_ORDER.put("Uncommon",  1);
        RARITY_ORDER.put("Rare",      2);
        RARITY_ORDER.put("Epic",      3);
        RARITY_ORDER.put("Legendary", 4);
        RARITY_ORDER.put("Mythic",    5);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────────

    public GenericResponseDTO<AssignmentStatsDTO> computeStats(AssignmentConfigInputDTO input) {
        String guildApiKey = sysConfigRepository.getValue("API-KEY")
                .orElseThrow(() -> new IllegalStateException("API-KEY non configurata"));

        // --- 1. Fetch current season + 4 previous ---
        Map<String, Object> currentData = callApi(guildApiKey, null);
        int currentSeason = ((Number) currentData.getOrDefault("season", 0)).intValue();
        List<SeasonData> allSeasons = new ArrayList<>();
        allSeasons.add(new SeasonData(currentSeason, currentData));
        for (int i = 1; i <= 4; i++) {
            try {
                Map<String, Object> prev = callApi(guildApiKey, currentSeason - i);
                allSeasons.add(new SeasonData(currentSeason - i, prev));
            } catch (Exception ignored) {
                // Older seasons may not exist – skip silently
            }
        }

        // --- 2. Build lookup: boss description → list of possible API type strings ---
        Map<String, String> unitNameToType = buildUnitNameToTypeMap(allSeasons);

        // --- 3. Enabled players ---
        Map<String, PlayerDocument> enabledPlayers = playerRepository.findAllEnabled()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));

        // --- 4. Anag levels ordered ---
        List<com.godsofdeath.monitor.document.AnagLevelDocument> levels =
                anagLevelRepository.findAllOrderedById();

        // --- 5. Build per-boss stats map (key = apiType) ---
        // aggregated across all 5 seasons, only Legendary/Mythic, only Battle
        Map<String, BossStats> statsMap = aggregateStats(allSeasons, enabledPlayers);

        // --- 6. Build output for each configured level/boss ---
        List<AssignmentStatsDTO.ConfiguredBossDTO> configuredBosses = new ArrayList<>();
        for (AssignmentConfigInputDTO.LevelBossEntry entry : input.getLevels()) {
            Optional<AnagBossDocument> bossDoc = anagBossRepository.findById(entry.getBossId());
            if (bossDoc.isEmpty()) continue;

            String bossDesc = bossDoc.get().getDescrizione();
            String levelDesc = levels.stream()
                    .filter(l -> l.getId() == entry.getLevelId())
                    .map(com.godsofdeath.monitor.document.AnagLevelDocument::getDescrizione)
                    .findFirst().orElse("L?");

            // Resolve boss description → API type string
            String apiType = unitNameToType.getOrDefault(bossDesc.toLowerCase(), null);
            if (apiType == null) {
                // Try partial match
                for (Map.Entry<String, String> e : unitNameToType.entrySet()) {
                    if (e.getKey().contains(bossDesc.toLowerCase())
                            || bossDesc.toLowerCase().contains(e.getKey())) {
                        apiType = e.getValue();
                        break;
                    }
                }
            }
            if (apiType == null) continue;

            BossStats bossStats = statsMap.get(apiType);
            if (bossStats == null) continue;

            // Build player stats for the boss
            List<AssignmentStatsDTO.PlayerStatDTO> playerStatList =
                    buildPlayerStats(bossStats.bossUnit, enabledPlayers, bossStats.guildBossAvg);

            // Build minis list
            List<AssignmentStatsDTO.MiniDTO> minis = new ArrayList<>();
            for (Map.Entry<String, UnitStats> miniEntry :
                    bossStats.miniUnitsOrdered.entrySet()) {
                String miniKey  = miniEntry.getKey();
                UnitStats us    = miniEntry.getValue();
                double guildAvg = us.guildAttackCount > 0
                        ? (double) us.guildDamageSum / us.guildAttackCount : 0;
                String miniName = resolveName(us.displayUnitId);

                minis.add(AssignmentStatsDTO.MiniDTO.builder()
                        .unitId(us.displayUnitId)
                        .name(miniName)
                        .encounterIndex(us.encounterIndex)
                        .guildAverage(Math.round(guildAvg * 100.0) / 100.0)
                        .playerStats(buildPlayerStats(us, enabledPlayers, guildAvg))
                        .build());
            }

            configuredBosses.add(AssignmentStatsDTO.ConfiguredBossDTO.builder()
                    .levelId(entry.getLevelId())
                    .levelDesc(levelDesc)
                    .bossId(entry.getBossId())
                    .bossDesc(bossDesc)
                    .apiType(apiType)
                    .guildAverage(Math.round(bossStats.guildBossAvg * 100.0) / 100.0)
                    .playerStats(playerStatList)
                    .minis(minis)
                    .build());
        }

        // --- 7. Compute initial assignments ---
        List<AssignmentStatsDTO.PlayerAssignmentDTO> assignments =
                computeInitialAssignments(configuredBosses, enabledPlayers);

        AssignmentStatsDTO result = AssignmentStatsDTO.builder()
                .currentSeason(currentSeason)
                .bosses(configuredBosses)
                .playerAssignments(assignments)
                .build();

        return GenericResponseDTO.ok("Stats calcolate", result);
    }

    public GenericResponseDTO<Void> saveAssignment(SaveAssignmentInputDTO input) {
        AssignmentDocument doc = new AssignmentDocument();
        doc.setName(input.getName());
        doc.setSeasonNumber(input.getSeasonNumber());
        doc.setCreatedAt(Instant.now().toString());
        doc.setAssignmentData(input.getAssignmentData());
        assignmentRepository.save(doc);
        return GenericResponseDTO.ok("Salvataggio effettuato");
    }

    public GenericResponseDTO<List<SavedAssignmentListDTO>> listSavedAssignments() {
        List<SavedAssignmentListDTO> list = assignmentRepository.findLast3()
                .stream()
                .map(d -> SavedAssignmentListDTO.builder()
                        .name(d.getName())
                        .seasonNumber(d.getSeasonNumber())
                        .createdAt(d.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
        return GenericResponseDTO.ok("Lista recuperata", list);
    }

    public GenericResponseDTO<String> loadAssignment(String name, int seasonNumber) {
        return assignmentRepository.findByNameAndSeason(name, seasonNumber)
                .map(d -> GenericResponseDTO.ok("Assignment caricato", d.getAssignmentData()))
                .orElseGet(() -> GenericResponseDTO.ko("Assignment non trovato"));
    }

    public boolean existsAssignment(String name, int seasonNumber) {
        return assignmentRepository.existsByNameAndSeason(name, seasonNumber);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Builds a map: normalized boss name (lowercase) → best API type string.
     * "Best" means highest rarity; ties broken by latest position in combined entries.
     */
    private Map<String, String> buildUnitNameToTypeMap(List<SeasonData> allSeasons) {
        // For each API type, track the best (rarity, position) seen
        Map<String, BestRarityEntry> bestByType = new LinkedHashMap<>();

        int globalPos = 0;
        for (SeasonData sd : allSeasons) {
            List<Map<String, Object>> entries = getEntries(sd.data);
            for (Map<String, Object> e : entries) {
                String type   = str(e, "type");
                String rarity = str(e, "rarity");
                int rarityOrd = RARITY_ORDER.getOrDefault(rarity, -1);
                if (rarityOrd < 0) { globalPos++; continue; }

                BestRarityEntry cur = bestByType.get(type);
                if (cur == null || rarityOrd > cur.rarityOrder
                        || (rarityOrd == cur.rarityOrder && globalPos > cur.position)) {
                    bestByType.put(type, new BestRarityEntry(rarityOrd, globalPos, type));
                }
                globalPos++;
            }
        }

        // Now map: unitName (lowercase) → type
        Map<String, String> result = new HashMap<>();
        for (String apiType : bestByType.keySet()) {
            String unitName = resolveName(apiType);
            result.put(unitName.toLowerCase(), apiType);
            // Also put the type itself lowercased for direct matching
            result.put(apiType.toLowerCase(), apiType);
        }
        // Add boss_lookup entries for direct name coverage
        return result;
    }

    /**
     * Aggregates damage stats across all seasons.
     * Only Legendary/Mythic rarity, only Battle damageType.
     * Killing blow rules: Boss excludes remainingHp==0; SideBoss excludes remainingHp==0 unless damageDealt==maxHp.
     */
    private Map<String, BossStats> aggregateStats(List<SeasonData> allSeasons,
                                                   Map<String, PlayerDocument> enabledPlayers) {
        Map<String, BossStats> result = new LinkedHashMap<>();

        // Track the best rarity seen per apiType to resolve minis display unitId
        Map<String, BestRarityEntry> bestRarityByType = new LinkedHashMap<>();
        int globalPos = 0;

        for (SeasonData sd : allSeasons) {
            List<Map<String, Object>> entries = getEntries(sd.data);

            // Single pass: accumulate stats
            for (Map<String, Object> e : entries) {
                String rarity       = str(e, "rarity");
                String damageType   = str(e, "damageType");
                String encType      = str(e, "encounterType");
                String userId       = str(e, "userId");
                String apiType      = str(e, "type");
                String unitId       = str(e, "unitId");
                long   damageDealt  = toLong(e, "damageDealt");
                long   remainingHp  = toLong(e, "remainingHp");
                long   maxHp        = toLong(e, "maxHp");
                int    encIdx       = toInt(e, "encounterIndex");

                int rarityOrd = RARITY_ORDER.getOrDefault(rarity, -1);
                if (rarityOrd < RARITY_ORDER.get("Legendary")) { globalPos++; continue; }
                if (!"Battle".equals(damageType)) { globalPos++; continue; }
                if (!enabledPlayers.containsKey(userId)) { globalPos++; continue; }

                boolean isKill = (remainingHp == 0);

                if ("Boss".equals(encType)) {
                    // Update best rarity tracker
                    BestRarityEntry cur = bestRarityByType.get(apiType);
                    if (cur == null || rarityOrd > cur.rarityOrder
                            || (rarityOrd == cur.rarityOrder && globalPos > cur.position)) {
                        bestRarityByType.put(apiType, new BestRarityEntry(rarityOrd, globalPos, apiType));
                    }

                    BossStats bs = result.computeIfAbsent(apiType, k -> new BossStats());
                    bs.bossUnit.playerStats.computeIfAbsent(userId, k -> new PlayerUnitStats());
                    bs.bossUnit.playerStats.get(userId).attackCount++;

                    if (!isKill) {
                        bs.bossUnit.playerStats.get(userId).damageSum += damageDealt;
                        bs.bossUnit.playerStats.get(userId).validCount++;
                        bs.bossUnit.guildDamageSum  += damageDealt;
                        bs.bossUnit.guildAttackCount++;
                    }

                } else if ("SideBoss".equals(encType)) {
                    // For SideBoss entries the `type` field IS the parent boss type — use it directly.
                    String parentType = apiType;

                    // Mini key: parentType → tracked separately per unitId
                    BossStats bs = result.computeIfAbsent(parentType, k -> new BossStats());

                    // Build the canonical mini unit ID from the raw unitId
                    String miniKey = extractMiniTypeFromUnitId(unitId);

                    UnitStats miniStats = bs.miniUnitsOrdered.computeIfAbsent(
                            miniKey, k -> {
                                UnitStats us = new UnitStats();
                                us.displayUnitId = miniKey;
                                us.encounterIndex = encIdx;
                                return us;
                            });

                    // Keep the canonical unitId from highest-rarity encounter
                    BestRarityEntry miniCur = bestRarityByType.get(miniKey);
                    if (miniCur == null || rarityOrd > miniCur.rarityOrder
                            || (rarityOrd == miniCur.rarityOrder && globalPos > miniCur.position)) {
                        bestRarityByType.put(miniKey, new BestRarityEntry(rarityOrd, globalPos, miniKey));
                        miniStats.displayUnitId = miniKey;
                    }

                    miniStats.playerStats.computeIfAbsent(userId, k -> new PlayerUnitStats());
                    miniStats.playerStats.get(userId).attackCount++;

                    boolean sideValid = !isKill || (damageDealt == maxHp);
                    if (sideValid) {
                        miniStats.playerStats.get(userId).damageSum += damageDealt;
                        miniStats.playerStats.get(userId).validCount++;
                        miniStats.guildDamageSum  += damageDealt;
                        miniStats.guildAttackCount++;
                    }
                }
                globalPos++;
            }
        }

        // Compute guild boss averages
        for (BossStats bs : result.values()) {
            bs.guildBossAvg = bs.bossUnit.guildAttackCount > 0
                    ? (double) bs.bossUnit.guildDamageSum / bs.bossUnit.guildAttackCount : 0;
        }

        return result;
    }

    /**
     * Extracts the "meaningful" part of a raw unitId for mini bosses.
     * E.g. "GuildBoss7MiniBoss1AstraPrimarisPsy" → "AstraPrimarisPsy"
     */
    private String extractMiniTypeFromUnitId(String unitId) {
        // Strip "GuildBossXMiniBossY" prefix if present
        if (unitId.contains("MiniBoss")) {
            int idx = unitId.indexOf("MiniBoss");
            // skip "MiniBossN" (1 digit)
            String rest = unitId.substring(idx + "MiniBoss".length());
            // skip one digit
            if (!rest.isEmpty() && Character.isDigit(rest.charAt(0))) {
                rest = rest.substring(1);
            }
            return rest;
        }
        return unitId;
    }

    private List<AssignmentStatsDTO.PlayerStatDTO> buildPlayerStats(
            UnitStats unit,
            Map<String, PlayerDocument> enabledPlayers,
            double guildAvg) {

        return enabledPlayers.values().stream()
                .map(p -> {
                    PlayerUnitStats ps = unit.playerStats.getOrDefault(p.getUserId(), new PlayerUnitStats());
                    double avg = ps.validCount > 0 ? (double) ps.damageSum / ps.validCount : 0;
                    return AssignmentStatsDTO.PlayerStatDTO.builder()
                            .userId(p.getUserId())
                            .playerName(p.getUserGameName())
                            .average(Math.round(avg * 100.0) / 100.0)
                            .delta(Math.round((avg - guildAvg) * 100.0) / 100.0)
                            .attackCount(ps.attackCount)
                            .build();
                })
                .sorted(Comparator.comparingDouble(AssignmentStatsDTO.PlayerStatDTO::getDelta).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Computes initial player assignments.
     *
     * Per-player ranking:
     *   - top 3 bosses/minis by delta → "consigliato"
     *   - next 5 → "affrontabile"
     *   - rest → "sconsigliato"
     *
     * Then balancing: each boss needs ≥10 recommended, each mini ≥4.
     * Start from last-configured boss (reversed order), fill up by picking
     * the player with minimum current assignments AND best delta.
     */
    private List<AssignmentStatsDTO.PlayerAssignmentDTO> computeInitialAssignments(
            List<AssignmentStatsDTO.ConfiguredBossDTO> bosses,
            Map<String, PlayerDocument> enabledPlayers) {

        // All target keys: "apiType" for boss, "apiType__miniUnitId" for mini
        List<String> targetKeys = new ArrayList<>();
        Map<String, Double> guildAvgByKey = new HashMap<>();

        for (AssignmentStatsDTO.ConfiguredBossDTO b : bosses) {
            targetKeys.add(b.getApiType());
            guildAvgByKey.put(b.getApiType(), b.getGuildAverage());
            for (AssignmentStatsDTO.MiniDTO m : b.getMinis()) {
                String key = b.getApiType() + "__" + m.getUnitId();
                targetKeys.add(key);
                guildAvgByKey.put(key, m.getGuildAverage());
            }
        }

        // Per player: delta per target key
        Map<String, Map<String, Double>> playerDeltas = new HashMap<>();
        for (PlayerDocument p : enabledPlayers.values()) {
            Map<String, Double> deltas = new HashMap<>();
            for (AssignmentStatsDTO.ConfiguredBossDTO b : bosses) {
                double d = b.getPlayerStats().stream()
                        .filter(ps -> ps.getUserId().equals(p.getUserId()))
                        .mapToDouble(AssignmentStatsDTO.PlayerStatDTO::getDelta)
                        .findFirst().orElse(Double.NEGATIVE_INFINITY);
                deltas.put(b.getApiType(), d);
                for (AssignmentStatsDTO.MiniDTO m : b.getMinis()) {
                    double dm = m.getPlayerStats().stream()
                            .filter(ps -> ps.getUserId().equals(p.getUserId()))
                            .mapToDouble(AssignmentStatsDTO.PlayerStatDTO::getDelta)
                            .findFirst().orElse(Double.NEGATIVE_INFINITY);
                    deltas.put(b.getApiType() + "__" + m.getUnitId(), dm);
                }
            }
            playerDeltas.put(p.getUserId(), deltas);
        }

        // Initial assignment: for each player rank targets by delta
        Map<String, Map<String, String>> assignments = new HashMap<>();
        for (PlayerDocument p : enabledPlayers.values()) {
            Map<String, Double> deltas = playerDeltas.get(p.getUserId());
            List<String> ranked = targetKeys.stream()
                    .sorted(Comparator.comparingDouble(k -> -deltas.getOrDefault(k, Double.NEGATIVE_INFINITY)))
                    .collect(Collectors.toList());

            Map<String, String> pa = new HashMap<>();
            for (int i = 0; i < ranked.size(); i++) {
                String key = ranked.get(i);
                if (i < 3)       pa.put(key, "consigliato");
                else if (i < 8)  pa.put(key, "affrontabile");
                else              pa.put(key, "sconsigliato");
            }
            assignments.put(p.getUserId(), pa);
        }

        // Balancing phase
        // Required: boss ≥10 consigliati, mini ≥4 consigliati
        // Process bosses in REVERSE configuration order
        List<String> bossKeysReversed = new ArrayList<>();
        for (int i = bosses.size() - 1; i >= 0; i--) {
            AssignmentStatsDTO.ConfiguredBossDTO b = bosses.get(i);
            bossKeysReversed.add(b.getApiType());
            List<AssignmentStatsDTO.MiniDTO> minis = b.getMinis();
            for (int j = minis.size() - 1; j >= 0; j--) {
                bossKeysReversed.add(b.getApiType() + "__" + minis.get(j).getUnitId());
            }
        }

        List<String> playerIds = new ArrayList<>(enabledPlayers.keySet());

        for (String targetKey : bossKeysReversed) {
            int required = targetKey.contains("__") ? 4 : 10;
            while (countConsigliati(assignments, targetKey) < required) {
                // Find the player with minimum total assignments AND best delta for this target
                int minAssignments = playerIds.stream()
                        .mapToInt(pid -> countConsigliatiTotal(assignments.get(pid)))
                        .min().orElse(0);

                String bestPlayer = playerIds.stream()
                        .filter(pid -> countConsigliatiTotal(assignments.get(pid)) == minAssignments)
                        .filter(pid -> !"consigliato".equals(assignments.get(pid).getOrDefault(targetKey, "")))
                        .max(Comparator.comparingDouble(
                                pid -> playerDeltas.getOrDefault(pid, Collections.emptyMap())
                                        .getOrDefault(targetKey, Double.NEGATIVE_INFINITY)))
                        .orElse(null);

                if (bestPlayer == null) break; // no candidates left

                assignments.get(bestPlayer).put(targetKey, "consigliato");
            }
        }

        // Build output
        return enabledPlayers.values().stream()
                .map(p -> AssignmentStatsDTO.PlayerAssignmentDTO.builder()
                        .userId(p.getUserId())
                        .playerName(p.getUserGameName())
                        .assignments(assignments.getOrDefault(p.getUserId(), Collections.emptyMap()))
                        .build())
                .sorted(Comparator.comparing(AssignmentStatsDTO.PlayerAssignmentDTO::getPlayerName))
                .collect(Collectors.toList());
    }

    private int countConsigliati(Map<String, Map<String, String>> all, String key) {
        return (int) all.values().stream()
                .filter(m -> "consigliato".equals(m.getOrDefault(key, "")))
                .count();
    }

    private int countConsigliatiTotal(Map<String, String> pa) {
        if (pa == null) return 0;
        return (int) pa.values().stream().filter("consigliato"::equals).count();
    }

    private String resolveName(String typeOrUnitId) {
        Optional<String> exact = bossLookupRepository.findNameByUnitId(typeOrUnitId);
        if (exact.isPresent()) return exact.get();
        return bossLookupRepository.findNameByUnitIdContains(typeOrUnitId).orElse(typeOrUnitId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callApi(String apiKey, Integer seasonOverride) {
        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.set("accept", "application/json");

        String url = seasonOverride == null
                ? tacticusBaseUrl + "/guildRaid"
                : tacticusBaseUrl + "/guildRaid/" + seasonOverride;

        ResponseEntity<Map> response = rt.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
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

    // ────────────────────────────────────────────────────────────────────────
    // Inner helper classes
    // ────────────────────────────────────────────────────────────────────────

    private static class SeasonData {
        final int seasonId;
        final Map<String, Object> data;
        SeasonData(int s, Map<String, Object> d) { seasonId = s; data = d; }
    }

    private static class BestRarityEntry {
        int rarityOrder;
        int position;
        String typeId;
        BestRarityEntry(int r, int p, String t) { rarityOrder = r; position = p; typeId = t; }
    }

    private static class BossStats {
        UnitStats bossUnit = new UnitStats();
        double    guildBossAvg;
        /** Ordered map: miniKey → UnitStats (insertion order = encounter order). */
        Map<String, UnitStats> miniUnitsOrdered = new LinkedHashMap<>();
    }

    private static class UnitStats {
        String displayUnitId = "";
        int    encounterIndex;
        long   guildDamageSum;
        int    guildAttackCount;
        Map<String, PlayerUnitStats> playerStats = new HashMap<>();
    }

    private static class PlayerUnitStats {
        long damageSum;
        int  attackCount;
        int  validCount;
    }
}
