package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BaselineOutboxRepository extends JpaRepository<BaselineOutboxEntity, Long> {

    List<BaselineOutboxEntity> findTop50ByStatusOrderByIdAsc(String status);

    long countByStatus(String status);
}