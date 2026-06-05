package com.godsofdeath.monitor.dto.input;

import lombok.Data;

import java.util.List;

@Data
public class AssignmentConfigInputDTO {
    /** Mapping level → boss per la season configurata. */
    private List<LevelBossEntry> levels;

    @Data
    public static class LevelBossEntry {
        private int levelId;
        private int bossId;
    }
}
