package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.AdminUserDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Optional;

@Repository
public class AdminUserRepository {

    private final DynamoDbTable<AdminUserDocument> table;

    public AdminUserRepository(DynamoDbEnhancedClient client,
                               @Value("${dynamodb.tables.admin-users}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(AdminUserDocument.class));
    }

    public boolean isAdmin(String discordName) {
        AdminUserDocument doc = table.getItem(Key.builder().partitionValue(discordName).build());
        return doc != null && "Y".equals(doc.getEnabled());
    }
}
