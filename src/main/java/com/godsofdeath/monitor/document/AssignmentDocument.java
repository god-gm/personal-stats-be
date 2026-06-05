package com.godsofdeath.monitor.document;

import lombok.Data;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@Data
@DynamoDbBean
public class AssignmentDocument {

    private String name;
    private Integer seasonNumber;
    private String createdAt;
    private String assignmentData;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("NAME")
    public String getName() { return name; }

    @DynamoDbSortKey
    @DynamoDbAttribute("SEASON_NUMBER")
    public Integer getSeasonNumber() { return seasonNumber; }

    @DynamoDbAttribute("CREATED_AT")
    public String getCreatedAt() { return createdAt; }

    @DynamoDbAttribute("ASSIGNMENT_DATA")
    public String getAssignmentData() { return assignmentData; }
}
