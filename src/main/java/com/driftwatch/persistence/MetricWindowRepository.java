package com.driftwatch.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MetricWindowRepository extends JpaRepository<MetricWindowEntity, Long> {

    Optional<MetricWindowEntity> findBySourceAndEventTypeAndWindowStartAndWindowEndAndMetricName(
            String source, String eventType, Instant windowStart, Instant windowEnd, String metricName);

    @Query("""
            select m from MetricWindowEntity m
            where (:source is null or m.source = :source)
              and (:eventType is null or m.eventType = :eventType)
              and (:metricName is null or m.metricName = :metricName)
            order by m.windowStart desc, m.metricName asc, m.id desc
            """)
    Page<MetricWindowEntity> search(@Param("source") String source,
                                    @Param("eventType") String eventType,
                                    @Param("metricName") String metricName,
                                    Pageable pageable);

    @Query("""
            select m from MetricWindowEntity m
            where m.source = :source
              and m.eventType = :eventType
              and m.metricName = :metricName
              and m.windowEnd <= :cutoff
            order by m.windowStart desc, m.id desc
            """)
    List<MetricWindowEntity> findRecentCompleted(@Param("source") String source,
                                                 @Param("eventType") String eventType,
                                                 @Param("metricName") String metricName,
                                                 @Param("cutoff") Instant cutoff,
                                                 Pageable pageable);
}
