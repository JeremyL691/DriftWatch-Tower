package com.driftwatch.persistence;

import com.driftwatch.quality.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityAlertRepository extends JpaRepository<QualityAlertEntity, Long> {

    @org.springframework.data.jpa.repository.Query("""
            select a from QualityAlertEntity a
            where (:type is null or a.alertType = :type)
              and (:source is null or a.source = :source)
              and (:status is null or a.status = :status)
            order by a.createdAt desc, a.id desc
            """)
    Page<QualityAlertEntity> managementFilter(AlertType type, String source, String status, Pageable pageable);

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

    java.util.List<QualityAlertEntity> findByIngestionIdOrderByCreatedAtAsc(String ingestionId);

    java.util.List<QualityAlertEntity> findByIncidentIdAndStatusNot(Long incidentId, String status);

    long countByIncidentIdAndStatusNot(Long incidentId, String status);
}
