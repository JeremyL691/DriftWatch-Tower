package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceGapRepository extends JpaRepository<SourceGapEntity, Long> {

    List<SourceGapEntity> findByRecoveryStateOrderByIdAsc(String recoveryState);

    long countByRecoveryState(String recoveryState);
}
