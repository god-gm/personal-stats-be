package com.godsofdeath.monitor.dto.internal;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlayerDTO {
    private String userId;
    private String userGameName;
    private String discordName;
    private boolean enabled;
}
