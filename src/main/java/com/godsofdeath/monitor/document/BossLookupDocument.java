package com.godsofdeath.monitor.document;

import lombok.Data;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Data
@DynamoDbBean
public class BossLookupDocument {

    private String unitId;
    private String unitName;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("UNIT_ID")
    public String getUnitId() { return unitId; }

    @DynamoDbAttribute("UNIT_NAME")
    public String getUnitName() { return unitName; }
}
