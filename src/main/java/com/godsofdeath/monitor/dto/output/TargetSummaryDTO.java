package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class TargetSummaryDTO {
    private String key;
    private String label;
    private double guildAverage;
    private Map<String, Double> playerAverages;
}
