package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenUsageEntryDTO {
    private String playerName;
    private String discordName;
    private int    tokenCount;
}
