package com.godsofdeath.monitor.dto.output;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SavedAssignmentListDTO {
    private String name;
    private int    seasonNumber;
    private String createdAt;
}
