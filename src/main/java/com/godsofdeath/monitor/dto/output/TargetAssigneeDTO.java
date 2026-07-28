package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TargetAssigneeDTO {
    private String userId;
    private String playerName;
}
