package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchemaVersionRepository extends JpaRepository<SchemaVersionEntity, Long> {

    Optional<SchemaVersionEntity> findByEventTypeAndSchemaHash(String eventType, String schemaHash);

    Optional<SchemaVersionEntity> findFirstByEventTypeAndStatus(String eventType, String status);

    List<SchemaVersionEntity> findByEventTypeOrderByFirstSeenAtAsc(String eventType);

    List<SchemaVersionEntity> findAllByOrderByEventTypeAscFirstSeenAtAsc();
}
