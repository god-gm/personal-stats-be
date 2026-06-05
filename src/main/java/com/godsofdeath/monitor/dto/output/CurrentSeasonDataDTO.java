package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CurrentSeasonDataDTO {

    private int season;
    private String playerName;
    private int totalTokensUsed;
    private int totalBombsUsed;
    private List<BossGroupDTO> bossGroups;

    @Data
    @Builder
    public static class BossGroupDTO {
        private String label;      // "L1", "M2", ecc.
        private String bossName;   // nome decodificato dalla lookup
        private List<EncounterDTO> encounters;
    }

    @Data
    @Builder
    public static class EncounterDTO {
        private String unitId;
        private String name;
        private String encounterType;
        private double guildAverage;
        private double playerAverage;
        private int playerAttackCount;
        private String performanceIndicator;
    }
}
