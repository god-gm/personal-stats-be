package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.AnagLevelDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class AnagLevelRepository {

    private final DynamoDbTable<AnagLevelDocument> table;

    public AnagLevelRepository(DynamoDbEnhancedClient client,
                               @Value("${dynamodb.tables.anag-level}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(AnagLevelDocument.class));
    }

    public List<AnagLevelDocument> findAllOrderedById() {
        return table.scan(ScanEnhancedRequest.builder().build())
                .items()
                .stream()
                .sorted(Comparator.comparingInt(AnagLevelDocument::getId))
                .collect(Collectors.toList());
    }
}
