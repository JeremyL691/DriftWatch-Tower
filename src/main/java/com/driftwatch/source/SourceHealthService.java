package com.driftwatch.source;

import com.driftwatch.persistence.MetricWindowRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.persistence.SourceHealthEntity;
import com.driftwatch.persistence.SourceHealthRepository;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.DraftAlert;
import com.driftwatch.quality.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SourceHealthService {

    private static final String NULL_RATE_PREFIX = "NULL_RATE:";

    private final RawEventRepository rawEventRepository;
    private final QualityAlertRepository alertRepository;
    private final MetricWindowRepository metricWindowRepository;
    private final SourceHealthRepository sourceHealthRepository;
    private final SourceFreshnessPolicy freshnessPolicy;
    private final SourceHealthCalculator calculator;
    private final ObjectMapper objectMapper;

    public SourceHealthService(RawEventRepository rawEventRepository,
                               QualityAlertRepository alertRepository,
                               MetricWindowRepository metricWindowRepository,
                               SourceHealthRepository sourceHealthRepository,
                               SourceFreshnessPolicy freshnessPolicy,
                               SourceHealthCalculator calculator,
                               ObjectMapper objectMapper) {
        this.rawEventRepository = rawEventRepository;
        this.alertRepository = alertRepository;
        this.metricWindowRepository = metricWindowRepository;
        this.sourceHealthRepository = sourceHealthRepository;
        this.freshnessPolicy = freshnessPolicy;
        this.calculator = calculator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<QualityAlertEntity> refreshAllAndPersist(Instant now) {
        List<DraftAlert> drafts = new ArrayList<>();
        for (String source : rawEventRepository.findDistinctSources()) {
            refreshSource(source, now).ifPresent(drafts::add);
        }
        List<QualityAlertEntity> alerts = drafts.stream()
                .map(draft -> toEntity(draft, now))
                .toList();
        if (!alerts.isEmpty()) {
            alertRepository.saveAll(alerts);
        }
        return alerts;
    }

    // TODO: the refresh-on-read pattern here is a quick hack for the demo; real implementation
    //   should use a scheduled @Scheduled method or publish a domain event after ingestion
    @Transactional
    public List<SourceHealthEntity> list() {
        refreshAllAndPersist(Instant.now());
        return sourceHealthRepository.findAllByOrderByHealthScoreAscSourceAsc();
    }

    @Transactional
    public Optional<SourceHealthEntity> get(String source) {
        refreshAllAndPersist(Instant.now());
        return sourceHealthRepository.findById(source);
    }

    private Optional<DraftAlert> refreshSource(String source, Instant now) {
        Optional<RawEventEntity> latestOpt = rawEventRepository.findFirstBySourceOrderByEventTimestampDescIdDesc(source);
        if (latestOpt.isEmpty()) {
            return Optional.empty();
        }

        RawEventEntity latest = latestOpt.get();
        Duration staleAfter = freshnessPolicy.staleAfter(source, latest.getEventType());
        boolean stale = latest.getEventTimestamp().isBefore(now.minus(staleAfter));

        long eventsLast5m = rawEventRepository.countBySourceAndEventTimestampAfter(source, now.minus(Duration.ofMinutes(5)));
        long eventsLast1h = rawEventRepository.countBySourceAndEventTimestampAfter(source, now.minus(Duration.ofHours(1)));
        double duplicateRate = rate(
                rawEventRepository.countBySourceAndEventTimestampAfterAndQualityStatus(source, now.minus(Duration.ofHours(1)), "DUPLICATE"),
                eventsLast1h);
        double lateRate = rate(
                rawEventRepository.countBySourceAndEventTimestampAfterAndQualityStatus(source, now.minus(Duration.ofHours(1)), "LATE"),
                eventsLast1h);
        double nullRate = Optional.ofNullable(
                        metricWindowRepository.maxMetricValueBySourceAndMetricNamePrefixAndWindowEndAfter(
                                source, NULL_RATE_PREFIX, now.minus(Duration.ofHours(1))))
                .orElse(0.0d);
        long recentAlerts = alertRepository.countBySourceAndCreatedAtAfter(source, now.minus(Duration.ofHours(1)));

        SourceHealthCalculator.Inputs inputs = new SourceHealthCalculator.Inputs(
                duplicateRate, lateRate, nullRate, recentAlerts, stale);
        double healthScore = calculator.score(inputs);
        String status = calculator.status(healthScore, stale);

        SourceHealthEntity previous = sourceHealthRepository.findById(source).orElse(null);
        SourceHealthEntity row = previous != null ? previous : new SourceHealthEntity();
        row.setSource(source);
        row.setStatus(status);
        row.setLastSeenAt(latest.getEventTimestamp());
        row.setEventsLast5m(eventsLast5m);
        row.setEventsLast1h(eventsLast1h);
        row.setDuplicateRate(duplicateRate);
        row.setLateEventRate(lateRate);
        row.setNullRate(nullRate);
        row.setHealthScore(healthScore);
        row.setUpdatedAt(now);
        sourceHealthRepository.save(row);

        boolean wasStale = previous != null
                && SourceHealthEntity.STATUS_STALE.equals(previous.getStatus());
        if (stale && !wasStale) {
            return Optional.of(staleAlert(source, latest.getEventType(), latest.getEventTimestamp(), staleAfter, status, healthScore));
        }
        return Optional.empty();
    }

    private DraftAlert staleAlert(String source,
                                  String eventType,
                                  Instant lastSeenAt,
                                  Duration staleAfter,
                                  String status,
                                  double healthScore) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("source", source);
        evidence.put("last_seen_at", lastSeenAt.toString());
        evidence.put("stale_after", staleAfter.toString());
        evidence.put("status", status);
        evidence.put("health_score", healthScore);
        return new DraftAlert(
                AlertType.STALE_SOURCE,
                Severity.WARN,
                source,
                eventType,
                null,
                "Source " + source + " is stale; last event timestamp " + lastSeenAt,
                evidence
        );
    }

    private QualityAlertEntity toEntity(DraftAlert draft, Instant now) {
        QualityAlertEntity entity = new QualityAlertEntity();
        entity.setAlertType(draft.type());
        entity.setSeverity(draft.severity());
        entity.setSource(draft.source());
        entity.setEventType(draft.eventType());
        entity.setFieldPath(draft.fieldPath());
        entity.setMessage(draft.message());
        entity.setEvidenceJson(draft.evidence());
        entity.setCreatedAt(now);
        return entity;
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }
}
