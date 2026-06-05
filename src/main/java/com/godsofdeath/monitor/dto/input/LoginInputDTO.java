package com.godsofdeath.monitor.dto.input;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginInputDTO {

    @NotBlank(message = "Il campo discord_name non può essere vuoto")
    private String discordName;
}
