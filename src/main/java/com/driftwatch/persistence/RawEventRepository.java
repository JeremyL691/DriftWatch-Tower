package com.driftwatch.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawEventRepository extends JpaRepository<RawEventEntity, Long> {

    Page<RawEventEntity> findAllByOrderByReceivedAtDesc(Pageable pageable);

    boolean existsByEventId(String eventId);

    java.util.Optional<RawEventEntity> findFirstByPayloadHashAndReceivedAtAfterAndEventIdNot(
            String payloadHash, java.time.Instant cutoff, String eventId);

    long countBySourceAndEventTimestampAfter(String source, java.time.Instant cutoff);

    long countBySourceAndEventTimestampAfterAndQualityStatus(String source, java.time.Instant cutoff, String qualityStatus);

    java.util.Optional<RawEventEntity> findFirstBySourceOrderByEventTimestampDescIdDesc(String source);

    @org.springframework.data.jpa.repository.Query("select distinct r.source from RawEventEntity r order by r.source asc")
    java.util.List<String> findDistinctSources();
}
