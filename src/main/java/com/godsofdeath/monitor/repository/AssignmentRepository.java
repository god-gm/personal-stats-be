package com.godsofdeath.monitor.repository;

import com.godsofdeath.monitor.document.AssignmentDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class AssignmentRepository {

    private final DynamoDbTable<AssignmentDocument> table;

    public AssignmentRepository(DynamoDbEnhancedClient client,
                                @Value("${dynamodb.tables.assignments}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(AssignmentDocument.class));
    }

    public void save(AssignmentDocument doc) {
        table.putItem(doc);
    }

    public Optional<AssignmentDocument> findByNameAndSeason(String name, int seasonNumber) {
        return Optional.ofNullable(
                table.getItem(Key.builder()
                        .partitionValue(name)
                        .sortValue(seasonNumber)
                        .build()));
    }

    /** Returns the last 3 saved entries ordered by CREATED_AT descending. */
    public List<AssignmentDocument> findLast3() {
        return table.scan(ScanEnhancedRequest.builder().build())
                .items()
                .stream()
                .sorted(Comparator.comparing(AssignmentDocument::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(3)
                .collect(Collectors.toList());
    }

    public boolean existsByNameAndSeason(String name, int seasonNumber) {
        return findByNameAndSeason(name, seasonNumber).isPresent();
    }
}
