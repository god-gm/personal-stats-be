package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.BossLookupDocument;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class BossLookupRepository {

    private final DynamoDbTable<BossLookupDocument> table;
    private Map<String, String> cache; // unitId → unitName

    public BossLookupRepository(DynamoDbEnhancedClient client,
                                 @Value("${dynamodb.tables.boss-lookup}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(BossLookupDocument.class));
    }

    @PostConstruct
    void loadCache() {
        cache = table.scan(ScanEnhancedRequest.builder().build())
                .items()
                .stream()
                .collect(Collectors.toMap(BossLookupDocument::getUnitId, BossLookupDocument::getUnitName));
    }

    /** Lookup esatto per UNIT_ID. */
    public Optional<String> findNameByUnitId(String unitId) {
        return Optional.ofNullable(cache.get(unitId));
    }

    /**
     * Fallback case-insensitive: cerca i record il cui UNIT_ID è sottostringa
     * (case-insensitive) di unitId, e restituisce quello con il match più lungo
     * (più specifico) per evitare ambiguità tipo Tervigon vs TervigonLeviathan.
     */
    public Optional<String> findNameByUnitIdContains(String unitId) {
        String unitIdLower = unitId.toLowerCase();
        return cache.entrySet().stream()
                .filter(e -> unitIdLower.contains(e.getKey().toLowerCase()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue);
    }
}
