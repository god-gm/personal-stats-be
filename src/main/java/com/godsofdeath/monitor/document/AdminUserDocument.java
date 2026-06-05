package com.godsofdeath.monitor.document;

import lombok.Data;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Data
@DynamoDbBean
public class AdminUserDocument {

    private String userId;
    private String enabled;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("USER_ID")
    public String getUserId() { return userId; }

    @DynamoDbAttribute("ENABLED")
    public String getEnabled() { return enabled; }
}
