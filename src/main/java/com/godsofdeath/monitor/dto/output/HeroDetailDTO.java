package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HeroDetailDTO {
    private String unitId;
    private int    power;
}
