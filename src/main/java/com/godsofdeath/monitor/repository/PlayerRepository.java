package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.PlayerDocument;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class PlayerRepository {

    private final DynamoDbTable<PlayerDocument> table;
    private Map<String, PlayerDocument> cache; // userId → PlayerDocument

    public PlayerRepository(DynamoDbEnhancedClient client,
                            @Value("${dynamodb.tables.players}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(PlayerDocument.class));
    }

    @PostConstruct
    void loadCache() {
        cache = table.scan(ScanEnhancedRequest.builder().build())
                .items()
                .stream()
                .collect(Collectors.toMap(PlayerDocument::getUserId, p -> p));
    }

    public Optional<PlayerDocument> findByDiscordNameIgnoreCase(String discordName) {
        return cache.values().stream()
                .filter(p -> "Y".equals(p.getEnabled())
                        && discordName.equalsIgnoreCase(stripAt(p.getDiscordName())))
                .findFirst();
    }

    public List<PlayerDocument> findAllEnabled() {
        return cache.values().stream()
                .filter(p -> "Y".equals(p.getEnabled()))
                .collect(Collectors.toList());
    }

    public Optional<PlayerDocument> findById(String userId) {
        return Optional.ofNullable(cache.get(userId));
    }

    private String stripAt(String name) {
        if (name == null) return "";
        return name.startsWith("@") ? name.substring(1) : name;
    }
}
