package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.SysConfigDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Optional;

@Repository
public class SysConfigRepository {

    private final DynamoDbTable<SysConfigDocument> table;

    public SysConfigRepository(DynamoDbEnhancedClient client,
                                @Value("${dynamodb.tables.config}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(SysConfigDocument.class));
    }

    public Optional<String> getValue(String key) {
        SysConfigDocument item = table.getItem(Key.builder().partitionValue(key).build());
        return Optional.ofNullable(item).map(SysConfigDocument::getValue);
    }
}
