package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class SeasonPlayerDetailDTO {
    private String playerName;
    private List<PlayerEncounterDetailDTO> encounters;
}
