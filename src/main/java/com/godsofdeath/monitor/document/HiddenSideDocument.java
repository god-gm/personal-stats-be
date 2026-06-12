package com.godsofdeath.monitor.document;

import lombok.Data;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Data
@DynamoDbBean
public class HiddenSideDocument {

    private String assignmentName;
    /** JSON array of side keys, e.g. ["3_RogalDorn__AstraPrimarisPsy", "1_Abaddon__MiniBoss1"]. */
    private String sideKeys;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("ASSIGNMENT_NAME")
    public String getAssignmentName() { return assignmentName; }

    @DynamoDbAttribute("SIDE_KEYS")
    public String getSideKeys() { return sideKeys; }
}
