package com.godsofdeath.monitor.dto.input;

import lombok.Data;

import java.util.List;

@Data
public class SaveAssignmentInputDTO {
    private String name;
    private int    seasonNumber;
    /** JSON blob con la composizione completa (livelli + assegnazioni player). */
    private String assignmentData;
    /** Keys dei mini da nascondere nella dashboard, es. ["3_RogalDorn__AstraPrimarisPsy"]. */
    private List<String> hiddenSides;
}
