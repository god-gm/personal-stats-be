package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlayerSummaryDTO {
    private String userId;
    private String userGameName;
}
