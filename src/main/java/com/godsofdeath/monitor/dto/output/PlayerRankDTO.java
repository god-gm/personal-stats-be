package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlayerRankDTO {
    private String userId;
    private String playerName;
    private double pciPercent;
    private int    validAttackCount;
}
