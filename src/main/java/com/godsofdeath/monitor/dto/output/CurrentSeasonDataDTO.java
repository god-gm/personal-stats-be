package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CurrentSeasonDataDTO {

    private int season;
    private String playerName;
    /** "V" | "R" | null/altro — guida il filtro badge in dashboard */
    private String playerType;
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
        private double playerBest;
        private int playerAttackCount;
        private String performanceIndicator;
        /** "consigliato" | "affrontabile" | "sconsigliato" | "prioritario" | null (no saved assignment) */
        private String assignmentType;
    }
}
