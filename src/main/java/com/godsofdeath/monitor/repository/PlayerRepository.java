package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.PlayerDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class PlayerRepository {

    private final DynamoDbTable<PlayerDocument> table;

    public PlayerRepository(DynamoDbEnhancedClient client,
                            @Value("${dynamodb.tables.players}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(PlayerDocument.class));
    }

    public Optional<PlayerDocument> findByDiscordNameIgnoreCase(String discordName) {
        // Scan con FilterExpression: in produzione considera un GSI su DISCORD_NAME
        return table.scan(ScanEnhancedRequest.builder().build())
                .items()
                .stream()
                .filter(p -> "Y".equals(p.getEnabled())
                        && discordName.equalsIgnoreCase(stripAt(p.getDiscordName())))
                .findFirst();
    }

    public List<PlayerDocument> findAllEnabled() {
        return table.scan(ScanEnhancedRequest.builder().build())
                .items()
                .stream()
                .filter(p -> "Y".equals(p.getEnabled()))
                .collect(Collectors.toList());
    }

    public Optional<PlayerDocument> findById(String userId) {
        return Optional.ofNullable(
                table.getItem(Key.builder().partitionValue(userId).build()));
    }

    private String stripAt(String name) {
        if (name == null) return "";
        return name.startsWith("@") ? name.substring(1) : name;
    }
}
