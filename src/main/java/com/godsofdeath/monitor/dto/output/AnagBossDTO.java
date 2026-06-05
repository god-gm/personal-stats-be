package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AnagBossDTO {
    private int    id;
    private String descrizione;
    private String replayLink;
}
