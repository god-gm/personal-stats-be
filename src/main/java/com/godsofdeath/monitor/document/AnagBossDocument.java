package com.godsofdeath.monitor.document;

import lombok.Data;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Data
@DynamoDbBean
public class AnagBossDocument {

    private Integer id;
    private String descrizione;
    private String codifica;
    private String replayLink;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("ID")
    public Integer getId() { return id; }

    @DynamoDbAttribute("DESCRIZIONE")
    public String getDescrizione() { return descrizione; }

    @DynamoDbAttribute("CODIFICA")
    public String getCodifica() { return codifica; }

    @DynamoDbAttribute("REPLAY_LINK")
    public String getReplayLink() { return replayLink; }
}
