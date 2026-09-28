package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class SeasonRankingDTO {
    private int          season;
    private List<Integer> availableSeasons;
    private List<PlayerRankDTO> ranking;
}
