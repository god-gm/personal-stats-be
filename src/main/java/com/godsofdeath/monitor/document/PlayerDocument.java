package com.godsofdeath.monitor.document;

import lombok.Data;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Data
@DynamoDbBean
public class PlayerDocument {

    private String userId;
    private String userGameName;
    private String apiKey;
    private String discordName;
    private String enabled;
    private String playerType;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("USER_ID")
    public String getUserId() { return userId; }

    @DynamoDbAttribute("USER_GAME_NAME")
    public String getUserGameName() { return userGameName; }

    @DynamoDbAttribute("API_KEY")
    public String getApiKey() { return apiKey; }

    @DynamoDbAttribute("DISCORD_NAME")
    public String getDiscordName() { return discordName; }

    @DynamoDbAttribute("ENABLED")
    public String getEnabled() { return enabled; }

    @DynamoDbAttribute("PLAYER_TYPE")
    public String getPlayerType() { return playerType; }
}
