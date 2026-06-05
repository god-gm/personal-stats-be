package com.godsofdeath.monitor.document;

import lombok.Data;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Data
@DynamoDbBean
public class AnagLevelDocument {

    private Integer id;
    private String descrizione;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("ID")
    public Integer getId() { return id; }

    @DynamoDbAttribute("DESCRIZIONE")
    public String getDescrizione() { return descrizione; }
}
