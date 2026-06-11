package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlertIncidentRepository extends JpaRepository<AlertIncidentEntity, Long> {

    List<AlertIncidentEntity> findAllByOrderByCreatedAtDesc();

    List<AlertIncidentEntity> findByStatusOrderByCreatedAtDesc(String status);

    Optional<AlertIncidentEntity> findFirstBySourceAndEventTypeAndStatusOrderByCreatedAtDesc(
            String source, String eventType, String status);

    long countByStatus(String status);
}
