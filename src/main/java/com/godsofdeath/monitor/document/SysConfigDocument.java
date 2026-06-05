package com.godsofdeath.monitor.document;

import lombok.Data;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Data
@DynamoDbBean
public class SysConfigDocument {

    private String name;
    private String value;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("name")
    public String getName() { return name; }

    @DynamoDbAttribute("value")
    public String getValue() { return value; }
}
