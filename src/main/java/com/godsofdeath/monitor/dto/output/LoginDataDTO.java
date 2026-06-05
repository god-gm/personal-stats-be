package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginDataDTO {
    private String token;
    private String userGameName;
    private String role;
}
