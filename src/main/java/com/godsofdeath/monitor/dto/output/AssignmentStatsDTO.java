package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AssignmentStatsDTO {

    private int    currentSeason;
    private List<ConfiguredBossDTO> bosses;
    /** Initial assignments computed by the algorithm. */
    private List<PlayerAssignmentDTO> playerAssignments;

    @Data
    @Builder
    public static class ConfiguredBossDTO {
        private int    levelId;
        private String levelDesc;
        private int    bossId;
        private String bossDesc;
        /** API type value (e.g. "RogalDorn") used to link to the API data. */
        private String apiType;
        private double guildAverage;
        private List<PlayerStatDTO> playerStats;
        private List<MiniDTO>       minis;
        /** True when no API battle data exists yet; all assignments are forced to sconsigliato. */
        private boolean noStats;
    }

    @Data
    @Builder
    public static class MiniDTO {
        private String unitId;
        private String name;
        private int    encounterIndex;
        private double guildAverage;
        private List<PlayerStatDTO> playerStats;
    }

    @Data
    @Builder
    public static class PlayerStatDTO {
        private String userId;
        private String playerName;
        private double average;
        /** Difference player average - guild average (positive = better than guild). */
        private double delta;
        private int    attackCount;
    }

    @Data
    @Builder
    public static class PlayerAssignmentDTO {
        private String userId;
        private String playerName;
        /** Key = "bossApiType" or "bossApiType__miniUnitId", value = "consigliato"|"affrontabile"|"sconsigliato". */
        private Map<String, String> assignments;
    }
}
