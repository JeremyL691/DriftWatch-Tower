package com.driftwatch.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeadLetterRecordRepository extends JpaRepository<DeadLetterRecordEntity, Long> {

    Optional<DeadLetterRecordEntity> findByDiagnosticId(String diagnosticId);

    /** Operator lists are newest first on every branch, so pages do not shift between requests. */
    Page<DeadLetterRecordEntity> findAllByOrderByIdDesc(Pageable pageable);

    Page<DeadLetterRecordEntity> findByStageOrderByIdDesc(String stage, Pageable pageable);

    Page<DeadLetterRecordEntity> findByRecoveryStateOrderByIdDesc(String recoveryState, Pageable pageable);

    Page<DeadLetterRecordEntity> findByStageAndRecoveryStateOrderByIdDesc(String stage, String recoveryState,
                                                                          Pageable pageable);

    long countByRecoveryState(String recoveryState);
}
