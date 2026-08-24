package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PlayerTokenBreakdownResultDTO {
    private int season;
    private List<PlayerTokenBreakdownDTO> players;
    private List<TargetSummaryDTO> targets;
}
