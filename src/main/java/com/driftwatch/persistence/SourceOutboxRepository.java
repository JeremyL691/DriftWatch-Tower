package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceOutboxRepository extends JpaRepository<SourceOutboxEntity, Long> {

    List<SourceOutboxEntity> findTop100ByStatusOrderByIdAsc(String status);

    List<SourceOutboxEntity> findBySourceAndStatusOrderByIdAsc(String source, String status);

    long countByStatus(String status);
}
