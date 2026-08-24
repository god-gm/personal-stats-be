package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlayerTokenBreakdownDTO {
    private String userId;
    private String playerName;
    private int totalTokens;
    private int consigliatoTokens;
    private int affrontabileTokens;
    private int sconsigliatiTokens;
    private Integer lostTokens;
}
