package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TargetAttackStatDTO {
    private String userId;
    private String playerName;
    private int    attackCount;
    private double average;
}
