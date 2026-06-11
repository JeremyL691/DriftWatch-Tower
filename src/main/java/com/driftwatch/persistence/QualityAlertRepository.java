package com.driftwatch.persistence;

import com.driftwatch.quality.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityAlertRepository extends JpaRepository<QualityAlertEntity, Long> {

    Page<QualityAlertEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<QualityAlertEntity> findByAlertTypeOrderByCreatedAtDesc(AlertType alertType, Pageable pageable);

    Page<QualityAlertEntity> findBySourceOrderByCreatedAtDesc(String source, Pageable pageable);

    Page<QualityAlertEntity> findByAlertTypeAndSourceOrderByCreatedAtDesc(AlertType alertType,
                                                                          String source,
                                                                          Pageable pageable);

    Page<QualityAlertEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    long countBySourceAndCreatedAtAfter(String source, java.time.Instant cutoff);

    long countByCreatedAtAfter(java.time.Instant cutoff);

    long countByStatus(String status);
}
