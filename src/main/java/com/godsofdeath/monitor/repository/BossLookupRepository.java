package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.BossLookupDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.Comparator;
import java.util.Optional;

@Repository
public class BossLookupRepository {

    private final DynamoDbTable<BossLookupDocument> table;

    public BossLookupRepository(DynamoDbEnhancedClient client,
                                 @Value("${dynamodb.tables.boss-lookup}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(BossLookupDocument.class));
    }

    /** Lookup esatto per UNIT_ID. */
    public Optional<String> findNameByUnitId(String unitId) {
        BossLookupDocument item = table.getItem(Key.builder().partitionValue(unitId).build());
        return Optional.ofNullable(item).map(BossLookupDocument::getUnitName);
    }

    /**
     * Fallback case-insensitive: cerca i record il cui UNIT_ID è sottostringa
     * (case-insensitive) di unitId, e restituisce quello con il match più lungo
     * (più specifico) per evitare ambiguità tipo Tervigon vs TervigonLeviathan.
     */
    public Optional<String> findNameByUnitIdContains(String unitId) {
        String unitIdLower = unitId.toLowerCase();
        return table.scan(ScanEnhancedRequest.builder().build())
                .items()
                .stream()
                .filter(b -> unitIdLower.contains(b.getUnitId().toLowerCase()))
                .max(Comparator.comparingInt(b -> b.getUnitId().length()))
                .map(BossLookupDocument::getUnitName);
    }
}
