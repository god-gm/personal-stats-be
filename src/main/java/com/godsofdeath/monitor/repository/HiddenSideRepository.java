package com.godsofdeath.monitor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.godsofdeath.monitor.document.HiddenSideDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class HiddenSideRepository {

    private final DynamoDbTable<HiddenSideDocument> table;
    private final ObjectMapper objectMapper;

    public HiddenSideRepository(DynamoDbEnhancedClient client,
                                 @Value("${dynamodb.tables.hidden-sides}") String tableName,
                                 ObjectMapper objectMapper) {
        this.table        = client.table(tableName, TableSchema.fromBean(HiddenSideDocument.class));
        this.objectMapper = objectMapper;
    }

    public void save(String assignmentName, List<String> sideKeys) {
        try {
            HiddenSideDocument doc = new HiddenSideDocument();
            doc.setAssignmentName(assignmentName);
            doc.setSideKeys(objectMapper.writeValueAsString(sideKeys));
            table.putItem(doc);
        } catch (Exception e) {
            throw new RuntimeException("Errore nel salvataggio hidden sides", e);
        }
    }

    public List<String> findByAssignmentName(String assignmentName) {
        try {
            HiddenSideDocument doc = table.getItem(
                    Key.builder().partitionValue(assignmentName).build());
            if (doc == null || doc.getSideKeys() == null) return Collections.emptyList();
            return objectMapper.readValue(doc.getSideKeys(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
