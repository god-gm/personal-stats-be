package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PlayerInfoDTO {

    private String apiPlayerName;

    private int    tokensCurrent;
    private int    tokensMax;
    /** ISO-8601 Rome timezone — null when tokensCurrent == tokensMax */
    private String tokensNextAt;

    private boolean bombAvailable;
    /** ISO-8601 Rome timezone — null when bombAvailable */
    private String  bombNextAt;
}
