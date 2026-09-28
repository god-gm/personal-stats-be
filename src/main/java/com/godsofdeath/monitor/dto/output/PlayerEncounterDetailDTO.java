package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class PlayerEncounterDetailDTO {
    private String  unitId;
    private String  name;
    private String  encounterType;
    private String  rarity;
    private long    damageDealt;
    private long    maxHp;
    private long    remainingHp;
    private boolean killingBlow;
    private boolean excludedFromRanking;
    private double  guildAverage;
    private List<HeroDetailDTO> heroes;
    private HeroDetailDTO       machineOfWar;
}
