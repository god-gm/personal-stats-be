package com.godsofdeath.monitor.dto.input;

import lombok.Data;

@Data
public class SaveAssignmentInputDTO {
    private String name;
    private int    seasonNumber;
    /** JSON blob con la composizione completa (livelli + assegnazioni player). */
    private String assignmentData;
}
