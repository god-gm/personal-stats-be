package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GuildStatEntryDTO {
    private String playerName;
    private String discordName;
    private double average;
    private int    attackCount;
}
