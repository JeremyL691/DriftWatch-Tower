package com.driftwatch.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeadLetterRecordRepository extends JpaRepository<DeadLetterRecordEntity, Long> {

    Optional<DeadLetterRecordEntity> findByDiagnosticId(String diagnosticId);

    Page<DeadLetterRecordEntity> findByStageOrderByIdAsc(String stage, Pageable pageable);

    Page<DeadLetterRecordEntity> findByRecoveryStateOrderByIdAsc(String recoveryState, Pageable pageable);

    Page<DeadLetterRecordEntity> findByStageAndRecoveryStateOrderByIdAsc(String stage, String recoveryState,
                                                                        Pageable pageable);

    long countByRecoveryState(String recoveryState);
}
