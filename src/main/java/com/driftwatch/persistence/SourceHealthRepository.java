package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceHealthRepository extends JpaRepository<SourceHealthEntity, String> {

    List<SourceHealthEntity> findAllByOrderByHealthScoreAscSourceAsc();

    long countByStatusIn(java.util.Collection<String> statuses);
}
