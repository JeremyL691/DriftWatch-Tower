package com.driftwatch.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawEventRepository extends JpaRepository<RawEventEntity, Long> {

    Page<RawEventEntity> findAllByOrderByReceivedAtDesc(Pageable pageable);

    boolean existsByEventId(String eventId);

    java.util.Optional<RawEventEntity> findFirstByPayloadHashAndReceivedAtAfterAndEventIdNot(
            String payloadHash, java.time.Instant cutoff, String eventId);

    /**
     * Source health counts by when this application received the event, not by the upstream
     * event_timestamp: a backfilled or historic payload must not make a live source look quiet or
     * stale (guide 7.2).
     */
    long countBySourceAndReceivedAtAfter(String source, java.time.Instant cutoff);

    long countBySourceAndReceivedAtAfterAndQualityStatus(String source, java.time.Instant cutoff, String qualityStatus);

    java.util.Optional<RawEventEntity> findFirstBySourceOrderByReceivedAtDescIdDesc(String source);

    @org.springframework.data.jpa.repository.Query("select distinct r.source from RawEventEntity r order by r.source asc")
    java.util.List<String> findDistinctSources();

    java.util.Optional<RawEventEntity> findByIngestionId(String ingestionId);

    /**
     * Evaluation coverage (guide 5.3): how many events actually participated in a window, how many
     * were excluded and why, and how many ran without an active schema baseline. Without this,
     * "OK" reads as "every window participated" when in fact nothing was evaluated.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT
              count(*) AS total,
              count(*) FILTER (WHERE window_evaluation ->> 'outcome' = 'INCLUDED') AS included,
              count(*) FILTER (WHERE window_evaluation ->> 'outcome' = 'EXPIRED') AS expired,
              count(*) FILTER (WHERE window_evaluation ->> 'outcome' = 'FUTURE') AS future,
              count(*) FILTER (WHERE window_evaluation ->> 'outcome' = 'SKIPPED_MODE') AS skipped_mode,
              count(*) FILTER (WHERE window_evaluation IS NULL) AS unevaluated_missing,
              count(*) FILTER (WHERE baseline_status = 'APPLIED') AS baseline_applied,
              count(*) FILTER (WHERE baseline_status = 'PENDING') AS baseline_pending
            FROM raw_events
            WHERE received_at > :cutoff
            """, nativeQuery = true)
    java.util.Map<String, Object> evaluationCoverage(java.time.Instant cutoff);
}
