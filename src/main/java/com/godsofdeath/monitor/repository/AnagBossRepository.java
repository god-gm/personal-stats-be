package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.AnagBossDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class AnagBossRepository {

    private final DynamoDbTable<AnagBossDocument> table;

    public AnagBossRepository(DynamoDbEnhancedClient client,
                              @Value("${dynamodb.tables.anag-bosses}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(AnagBossDocument.class));
    }

    public List<AnagBossDocument> findAllOrderedById() {
        return table.scan(ScanEnhancedRequest.builder().build())
                .items()
                .stream()
                .sorted(Comparator.comparingInt(AnagBossDocument::getId))
                .collect(Collectors.toList());
    }

    public Optional<AnagBossDocument> findById(int id) {
        return Optional.ofNullable(
                table.getItem(Key.builder().partitionValue(id).build()));
    }
}
