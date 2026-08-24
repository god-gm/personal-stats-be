package com.godsofdeath.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.godsofdeath.monitor.document.AssignmentDocument;
import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.PlayerTokenBreakdownDTO;
import com.godsofdeath.monitor.dto.output.PlayerTokenBreakdownResultDTO;
import com.godsofdeath.monitor.dto.output.TargetSummaryDTO;
import com.godsofdeath.monitor.repository.AssignmentRepository;
import com.godsofdeath.monitor.repository.PlayerRepository;
import com.godsofdeath.monitor.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Computes the players-performances breakdown for the CURRENT season.
 *
 * Token Played  = all non-bomb attacks (all rarities) — same source as Token Usage.
 * % breakdown   = only Legendary/Mythic non-bomb attacks, classified by assignment type.
 * Target avgs   = current-season Battle/Leg+Myth/no-killing-blow — same logic as
 *                 AdminStatsService#getGuildStats / #getTargetAttackStats.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerPerformancesService {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private final PlayerRepository     playerRepository;
    private final SysConfigRepository  sysConfigRepository;
    private final AssignmentRepository assignmentRepository;
    private final ObjectMapper         objectMapper;

    @Value("${tacticus.api.base-url}")
    private String tacticusBaseUrl;

    // ── Simple accumulator for per-target damage stats ──────────────────────
    private static class TargetAcc {
        long guildDamageSum  = 0;
        int  guildValidCount = 0;
        final Map<String, long[]> playerStats = new HashMap<>(); // userId → [damageSum, validCount]

        void addPlayerHit(String userId, long damage) {
            long[] ps = playerStats.computeIfAbsent(userId, k -> new long[]{0L, 0L});
            ps[0] += damage;
            ps[1]++;
            guildDamageSum  += damage;
            guildValidCount++;
        }

        double playerAverage(String userId) {
            long[] ps = playerStats.get(userId);
            return (ps != null && ps[1] > 0) ? (double) ps[0] / ps[1] : 0.0;
        }

        double guildAverage() {
            return guildValidCount > 0 ? (double) guildDamageSum / guildValidCount : 0.0;
        }
    }

    public GenericResponseDTO<PlayerTokenBreakdownResultDTO> getPlayerTokenBreakdown() {

        // 1. Fetch current season data from Tacticus API
        Map<String, Object> apiData = fetchCurrentSeasonData();
        int currentSeason = ((Number) apiData.getOrDefault("season", 0)).intValue();
        List<Map<String, Object>> entries = extractEntries(apiData);

        // 2. Load the most recent assignment for the CURRENT season
        Optional<AssignmentDocument> savedOpt = assignmentRepository.findLatestBySeason(currentSeason);
        if (savedOpt.isEmpty()) {
            return GenericResponseDTO.ko(
                    "Nessun assignment trovato per la season corrente (" + currentSeason + ")");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(savedOpt.get().getAssignmentData());
        } catch (Exception e) {
            return GenericResponseDTO.ko("Errore nel parsing dell'assignment salvato");
        }

        // 3. Build target-key lookup maps and an ordered target list from the assignment blob.
        //    bossTypeToTargetKey : apiType               → "{levelId}_{apiType}"
        //    miniToTargetKey     : "parentType__miniKey" → "{levelId}_{parentType}__{miniKey}"
        //    orderedTargetKeys   : insertion-ordered list of all target keys
        //    targetMeta          : targetKey → { label }   (names come from the blob, avgs from API)
        Map<String, String>       bossTypeToTargetKey = new LinkedHashMap<>();
        Map<String, String>       miniToTargetKey     = new LinkedHashMap<>();
        List<String>              orderedTargetKeys   = new ArrayList<>();
        Map<String, String>       targetLabel         = new LinkedHashMap<>();
        Map<String, TargetAcc>    targetAccMap        = new LinkedHashMap<>();

        JsonNode bossesNode = root.path("stats").path("bosses");
        if (bossesNode.isArray()) {
            for (JsonNode boss : bossesNode) {
                String apiType       = boss.path("apiType").asText();
                int    levelId       = boss.path("levelId").asInt();
                String bossTargetKey = levelId + "_" + apiType;

                if (bossTypeToTargetKey.putIfAbsent(apiType, bossTargetKey) == null) {
                    orderedTargetKeys.add(bossTargetKey);
                    targetLabel.put(bossTargetKey, boss.path("bossDesc").asText());
                    targetAccMap.put(bossTargetKey, new TargetAcc());
                }

                JsonNode minisNode = boss.path("minis");
                if (minisNode.isArray()) {
                    for (JsonNode mini : minisNode) {
                        String miniUnitId    = mini.path("unitId").asText();
                        String miniTargetKey = bossTargetKey + "__" + miniUnitId;
                        String compositeKey  = apiType + "__" + miniUnitId;

                        if (miniToTargetKey.putIfAbsent(compositeKey, miniTargetKey) == null) {
                            orderedTargetKeys.add(miniTargetKey);
                            targetLabel.put(miniTargetKey, mini.path("name").asText());
                            targetAccMap.put(miniTargetKey, new TargetAcc());
                        }
                    }
                }
            }
        }

        // 4. Parse assignments: userId → { targetKey → assignmentType }
        Map<String, Map<String, String>> assignmentsMap = new HashMap<>();
        JsonNode assignmentsNode = root.path("assignments");
        if (assignmentsNode.isObject()) {
            assignmentsNode.fields().forEachRemaining(userEntry -> {
                Map<String, String> userAssignments = new HashMap<>();
                userEntry.getValue().fields().forEachRemaining(t ->
                        userAssignments.put(t.getKey(), t.getValue().asText()));
                assignmentsMap.put(userEntry.getKey(), userAssignments);
            });
        }

        // 5. Single pass over current-season entries
        //    Token totals (all rarities, no bomb)            → int[0]
        //    Leg/Myth consigliato tokens                     → int[1]
        //    Leg/Myth affrontabile tokens                    → int[2]
        //    Leg/Myth sconsigliato/uncategorized tokens      → int[3]
        Map<String, PlayerDocument> enabledPlayers = playerRepository.findAllEnabled()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));

        Map<String, int[]> playerCounts = new HashMap<>();

        for (Map<String, Object> entry : entries) {
            String userId        = str(entry, "userId");
            String damageType    = str(entry, "damageType");
            String rarity        = str(entry, "rarity");
            String encounterType = str(entry, "encounterType");
            String type          = str(entry, "type");
            String unitId        = str(entry, "unitId");
            long   damageDealt   = toLong(entry, "damageDealt");
            long   remainingHp   = toLong(entry, "remainingHp");
            long   maxHp         = toLong(entry, "maxHp");

            if (!enabledPlayers.containsKey(userId)) continue;

            boolean isBomb    = "Bomb".equals(damageType);
            boolean isLegMyth = "Legendary".equals(rarity) || "Mythic".equals(rarity);

            // Token Played — all non-bomb, all rarities
            if (!isBomb) {
                int[] c = playerCounts.computeIfAbsent(userId, k -> new int[4]);
                c[0]++;
            }

            // Leg/Myth processing
            if (!isBomb && isLegMyth) {
                // Resolve target key
                String targetKey = null;
                if ("Boss".equals(encounterType)) {
                    targetKey = bossTypeToTargetKey.get(type);
                } else if ("SideBoss".equals(encounterType)) {
                    targetKey = miniToTargetKey.get(type + "__" + extractMiniKey(unitId));
                }

                // Assignment-type token breakdown
                int[] c = playerCounts.computeIfAbsent(userId, k -> new int[4]);
                String assignment = null;
                if (targetKey != null) {
                    Map<String, String> ua = assignmentsMap.get(userId);
                    if (ua != null) assignment = ua.get(targetKey);
                }
                if      ("consigliato".equals(assignment))  c[1]++;
                else if ("affrontabile".equals(assignment)) c[2]++;
                else                                        c[3]++;

                // Target damage accumulator (Battle only, no killing blow)
                if ("Battle".equals(damageType) && targetKey != null && targetAccMap.containsKey(targetKey)) {
                    boolean isKillingBlow = "Boss".equals(encounterType)
                            ? remainingHp == 0
                            : "SideBoss".equals(encounterType) && remainingHp == 0 && damageDealt != maxHp;
                    if (!isKillingBlow) {
                        targetAccMap.get(targetKey).addPlayerHit(userId, damageDealt);
                    }
                }
            }
        }

        // 5b. Parallel fetch of individual player token info for the "lost tokens" computation.
        //     Season cutoff = last day of season (Tuesday, start+13 days) at 11:30 Rome time.
        //     Lost tokens = 29 - (used + current + tokens maturing before cutoff).
        Instant now           = Instant.now();
        Instant seasonCutoff  = entries.isEmpty() ? null : computeSeasonCutoff(entries);
        Map<String, long[]> playerTokenData = new ConcurrentHashMap<>();

        if (seasonCutoff != null && now.isBefore(seasonCutoff)) {
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(enabledPlayers.size(), 20));
            try {
                List<CompletableFuture<Void>> futures = enabledPlayers.values().stream()
                        .filter(p -> p.getApiKey() != null && !p.getApiKey().isBlank())
                        .map(p -> CompletableFuture.runAsync(() -> {
                            long[] info = fetchPlayerTokenInfo(p.getApiKey());
                            if (info != null) {
                                playerTokenData.put(p.getUserId(), info);
                            }
                        }, executor))
                        .collect(Collectors.toList());
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Some player token API calls failed or timed out: {}", e.getMessage());
            } finally {
                executor.shutdown();
            }
        }

        // 6. Build target summaries with current-season averages
        List<TargetSummaryDTO> targets = orderedTargetKeys.stream()
                .map(key -> {
                    TargetAcc acc = targetAccMap.get(key);
                    Map<String, Double> playerAverages = new LinkedHashMap<>();
                    for (String uid : enabledPlayers.keySet()) {
                        double avg = acc.playerAverage(uid);
                        if (avg > 0) playerAverages.put(uid, Math.round(avg * 100.0) / 100.0);
                    }
                    return TargetSummaryDTO.builder()
                            .key(key)
                            .label(targetLabel.get(key))
                            .guildAverage(Math.round(acc.guildAverage() * 100.0) / 100.0)
                            .playerAverages(playerAverages)
                            .build();
                })
                .collect(Collectors.toList());

        // 7. Build per-player result, sorted by player name ascending
        List<PlayerTokenBreakdownDTO> players = enabledPlayers.values().stream()
                .map(p -> {
                    int[] c = playerCounts.getOrDefault(p.getUserId(), new int[4]);
                    Integer lostTokens = null;
                    long[] tokenInfo = playerTokenData.get(p.getUserId());
                    if (tokenInfo != null && seasonCutoff != null) {
                        long tokensCurrent = tokenInfo[0];
                        long tokensMax     = tokenInfo[1];
                        long nextTokenSecs = tokenInfo[2];
                        int tokensToMature = computeTokensToMature(tokensCurrent, tokensMax, nextTokenSecs, now, seasonCutoff);
                        lostTokens = Math.max(0, 28 - (c[0] + (int) tokensCurrent + tokensToMature));
                    }
                    return PlayerTokenBreakdownDTO.builder()
                            .userId(p.getUserId())
                            .playerName(p.getUserGameName())
                            .totalTokens(c[0])
                            .consigliatoTokens(c[1])
                            .affrontabileTokens(c[2])
                            .sconsigliatiTokens(c[3])
                            .lostTokens(lostTokens)
                            .build();
                })
                .sorted(Comparator.comparing(PlayerTokenBreakdownDTO::getPlayerName,
                        String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        return GenericResponseDTO.ok(
                "Player token breakdown calcolato (season " + currentSeason + ")",
                PlayerTokenBreakdownResultDTO.builder()
                        .season(currentSeason)
                        .players(players)
                        .targets(targets)
                        .build());
    }

    /**
     * Derives the season end cutoff from the first entry's startedOn timestamp.
     * Seasons start on Wednesday; cutoff = that Tuesday 13 days later at 11:30 Rome time.
     */
    private Instant computeSeasonCutoff(List<Map<String, Object>> entries) {
        long firstStartedOn = toLong(entries.get(0), "startedOn");
        ZonedDateTime firstEntry = Instant.ofEpochSecond(firstStartedOn).atZone(ROME);

        // Find the most recent Wednesday on or before this date (= season start)
        int daysBack = Math.floorMod(
                firstEntry.getDayOfWeek().getValue() - DayOfWeek.WEDNESDAY.getValue(), 7);
        ZonedDateTime seasonStart = firstEntry.toLocalDate()
                .minusDays(daysBack)
                .atStartOfDay(ROME);

        // Season end = Tuesday (start + 13 days), cutoff at 11:30
        return seasonStart.plusDays(13)
                .withHour(11).withMinute(30).withSecond(0).withNano(0)
                .toInstant();
    }

    /**
     * Counts tokens that will mature between now and cutoff.
     * Each token takes 12 h; they mature sequentially starting from nextTokenInSecs from now.
     * If already at max capacity, no tokens are maturing so returns 0.
     */
    private int computeTokensToMature(long current, long max, long nextTokenInSecs, Instant now, Instant cutoff) {
        if (current >= max) return 0;
        int count = 0;
        Instant nextTokenTime = now.plusSeconds(nextTokenInSecs);
        while (!nextTokenTime.isAfter(cutoff)) {
            count++;
            nextTokenTime = nextTokenTime.plusSeconds(12L * 3600);
        }
        return count;
    }

    /**
     * Calls the Tacticus player API and returns [current, max, nextTokenInSeconds].
     * Returns null on any failure so the caller can skip this player gracefully.
     */
    @SuppressWarnings("unchecked")
    private long[] fetchPlayerTokenInfo(String apiKey) {
        try {
            RestTemplate rt = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", apiKey);
            headers.set("accept", "application/json");
            ResponseEntity<Map> response = rt.exchange(
                    tacticusBaseUrl + "/player",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return null;

            Map<String, Object> body      = response.getBody();
            Map<String, Object> player    = (Map<String, Object>) body.get("player");
            Map<String, Object> progress  = (Map<String, Object>) player.get("progress");
            Map<String, Object> guildRaid = (Map<String, Object>) progress.get("guildRaid");
            Map<String, Object> tokens    = (Map<String, Object>) guildRaid.get("tokens");

            long current    = toLong(tokens, "current");
            long max        = toLong(tokens, "max");
            long nextInSecs = current < max ? toLong(tokens, "nextTokenInSeconds") : 0L;
            return new long[]{current, max, nextInSecs};
        } catch (RestClientException e) {
            log.warn("Tacticus player API call failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Error parsing player token info: {}", e.getMessage());
            return null;
        }
    }

    /** Strips the "GuildBossXMiniBossY" prefix to get the canonical mini key. */
    private String extractMiniKey(String unitId) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchCurrentSeasonData() {
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
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractEntries(Map<String, Object> data) {
        Object e = data.get("entries");
        return e instanceof List ? (List<Map<String, Object>>) e : Collections.emptyList();
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
