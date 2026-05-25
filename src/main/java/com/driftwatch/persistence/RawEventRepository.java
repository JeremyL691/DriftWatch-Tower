package com.driftwatch.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawEventRepository extends JpaRepository<RawEventEntity, Long> {

    Page<RawEventEntity> findAllByOrderByReceivedAtDesc(Pageable pageable);

    boolean existsByEventId(String eventId);

    java.util.Optional<RawEventEntity> findFirstByPayloadHashAndReceivedAtAfterAndEventIdNot(
            String payloadHash, java.time.Instant cutoff, String eventId);
}
