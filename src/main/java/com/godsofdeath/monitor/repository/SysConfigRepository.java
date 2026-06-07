package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.SysConfigDocument;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class SysConfigRepository {

    private final DynamoDbTable<SysConfigDocument> table;
    private Map<String, String> cache; // name → value

    public SysConfigRepository(DynamoDbEnhancedClient client,
                                @Value("${dynamodb.tables.config}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(SysConfigDocument.class));
    }

    @PostConstruct
    void loadCache() {
        cache = table.scan(ScanEnhancedRequest.builder().build())
                .items()
                .stream()
                .collect(Collectors.toMap(SysConfigDocument::getName, SysConfigDocument::getValue));
    }

    public Optional<String> getValue(String key) {
        return Optional.ofNullable(cache.get(key));
    }
}
