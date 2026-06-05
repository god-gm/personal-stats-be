package com.godsofdeath.monitor.dto.input;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DiscordCallbackInputDTO {

    @NotBlank(message = "Il campo code non può essere vuoto")
    private String code;
}
