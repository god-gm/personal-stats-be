package com.godsofdeath.monitor.dto.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DiscordUserResponseDTO {

    private String id;
    private String username;
}
