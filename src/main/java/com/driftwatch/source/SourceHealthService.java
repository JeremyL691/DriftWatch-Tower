package com.driftwatch.source;

import com.driftwatch.operations.DriftwatchMetrics;
import com.driftwatch.persistence.CollectorStateEntity;
import com.driftwatch.persistence.CollectorStateRepository;
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

/**
 * Source health and STALE_SOURCE transitions (execution guide, section 7.2).
 *
 * <p>An ordinary push source is stale when its newest received_at is older than its freshness
 * threshold. A source that has a GitHub collector row is stale only when the collector is LOST,
 * using the collector's own rule, so historic upstream event timestamps never stand in for a
 * silent collector. Every new transition alert is correlated into an incident like sink alerts.
 */
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
    private final CollectorStateRepository collectorStateRepository;
    private final CollectorStatusService collectorStatusService;
    private final AlertIncidentService alertIncidentService;
    private final DriftwatchMetrics metrics;

    public SourceHealthService(RawEventRepository rawEventRepository,
                               QualityAlertRepository alertRepository,
                               MetricWindowRepository metricWindowRepository,
                               SourceHealthRepository sourceHealthRepository,
                               SourceFreshnessPolicy freshnessPolicy,
                               SourceHealthCalculator calculator,
                               ObjectMapper objectMapper,
                               CollectorStateRepository collectorStateRepository,
                               CollectorStatusService collectorStatusService,
                               AlertIncidentService alertIncidentService,
                               DriftwatchMetrics metrics) {
        this.rawEventRepository = rawEventRepository;
        this.alertRepository = alertRepository;
        this.metricWindowRepository = metricWindowRepository;
        this.sourceHealthRepository = sourceHealthRepository;
        this.freshnessPolicy = freshnessPolicy;
        this.calculator = calculator;
        this.objectMapper = objectMapper;
        this.collectorStateRepository = collectorStateRepository;
        this.collectorStatusService = collectorStatusService;
        this.alertIncidentService = alertIncidentService;
        this.metrics = metrics;
    }

    /** Full scan used by the scheduler: every registered source is checked. */
    @Transactional
    public List<QualityAlertEntity> refreshAllAndPersist(Instant now) {
        List<DraftAlert> drafts = new ArrayList<>();
        java.util.Set<String> allSources = new java.util.LinkedHashSet<>(rawEventRepository.findDistinctSources());
        collectorStateRepository.findAll().forEach(c -> allSources.add(c.getSource()));
        for (String source : allSources) {
            refreshSource(source, now).ifPresent(drafts::add);
        }
        if (drafts.isEmpty()) {
            return List.of();
        }
        List<QualityAlertEntity> alerts = alertRepository.saveAll(drafts.stream()
                .map(draft -> toEntity(draft, now))
                .toList());
        // Non-INFO alerts join an incident for their scope, exactly like sink alerts (guide 7.2).
        for (QualityAlertEntity alert : alerts) {
            alertIncidentService.correlate(alert);
            metrics.recordAlert(alert.getAlertType(), alert.getSeverity().name());
        }
        return alerts;
    }

    /** Pure read: a GET must never change state or add alerts (guide 7.2). */
    @Transactional(readOnly = true)
    public List<SourceHealthEntity> list() {
        return sourceHealthRepository.findAllByOrderByHealthScoreAscSourceAsc();
    }

    /** Pure read for one source. */
    @Transactional(readOnly = true)
    public Optional<SourceHealthEntity> get(String source) {
        return sourceHealthRepository.findById(source);
    }

    /**
     * Refresh used by the scheduler: scans the registered sources and persists the health rows and
     * transition alerts. Never called from a query path.
     */
    @Transactional
    public List<QualityAlertEntity> refreshScheduled(Instant now) {
        return refreshAllAndPersist(now);
    }

    private Optional<DraftAlert> refreshSource(String source, Instant now) {
        Optional<RawEventEntity> latestOpt = rawEventRepository.findFirstBySourceOrderByReceivedAtDescIdDesc(source);
        Optional<CollectorStateEntity> collector = collectorStateRepository.findById(source);
        if (latestOpt.isEmpty()) {
            if (collector.isEmpty()) {
                return Optional.empty();
            }
            CollectorStateEntity c = collector.get();
            boolean failing = c.getLastPollSuccess() == null
                    && (CollectorStateEntity.STATUS_ERROR.equals(c.getStatus()) || c.getConsecutiveFailures() >= 3);
            if (!failing) {
                return Optional.empty();
            }
            Duration staleAfter = collectorStatusService.lostAfter();
            Instant lastSeenAt = c.getLastPollAt() != null ? c.getLastPollAt() : now;
            double healthScore = 65.0d;
            String status = SourceHealthEntity.STATUS_STALE;

            SourceHealthEntity previous = sourceHealthRepository.findById(source).orElse(null);
            boolean wasStale = previous != null
                    && SourceHealthEntity.STATUS_STALE.equals(previous.getStatus());
            SourceHealthEntity row = previous != null ? previous : new SourceHealthEntity();
            row.setSource(source);
            row.setStatus(status);
            row.setLastSeenAt(lastSeenAt);
            row.setEventsLast5m(0L);
            row.setEventsLast1h(0L);
            row.setDuplicateRate(0.0d);
            row.setLateEventRate(0.0d);
            row.setNullRate(0.0d);
            row.setHealthScore(healthScore);
            row.setUpdatedAt(now);
            sourceHealthRepository.save(row);

            if (!wasStale) {
                return Optional.of(collectorNeverSucceededAlert(source, lastSeenAt, c, staleAfter, status, healthScore));
            }
            return Optional.empty();
        }

        RawEventEntity latest = latestOpt.get();
        Instant lastReceivedAt = latest.getReceivedAt();
        Duration staleAfter;
        boolean stale;
        String basis;
        if (collector.isPresent()) {
            staleAfter = collectorStatusService.lostAfter();
            stale = collectorStatusService.isLost(collector.get(), now);
            basis = "collector_last_poll_success";
        } else {
            staleAfter = freshnessPolicy.staleAfter(source, latest.getEventType());
            stale = lastReceivedAt.isBefore(now.minus(staleAfter));
            basis = "last_received_at";
        }

        Instant oneHourAgo = now.minus(Duration.ofHours(1));
        long eventsLast5m = rawEventRepository.countBySourceAndReceivedAtAfter(source, now.minus(Duration.ofMinutes(5)));
        long eventsLast1h = rawEventRepository.countBySourceAndReceivedAtAfter(source, oneHourAgo);
        double duplicateRate = rate(
                rawEventRepository.countBySourceAndReceivedAtAfterAndQualityStatus(source, oneHourAgo, "DUPLICATE"),
                eventsLast1h);
        double lateRate = rate(
                rawEventRepository.countBySourceAndReceivedAtAfterAndQualityStatus(source, oneHourAgo, "LATE"),
                eventsLast1h);
        double nullRate = Optional.ofNullable(
                        metricWindowRepository.maxMetricValueBySourceAndMetricNamePrefixAndWindowEndAfter(
                                source, NULL_RATE_PREFIX, oneHourAgo))
                .orElse(0.0d);
        long recentAlerts = alertRepository.countBySourceAndCreatedAtAfter(source, oneHourAgo);

        SourceHealthCalculator.Inputs inputs = new SourceHealthCalculator.Inputs(
                duplicateRate, lateRate, nullRate, recentAlerts, stale);
        double healthScore = calculator.score(inputs);
        String status = calculator.status(healthScore, stale);

        SourceHealthEntity previous = sourceHealthRepository.findById(source).orElse(null);
        boolean wasStale = previous != null
                && SourceHealthEntity.STATUS_STALE.equals(previous.getStatus());
        SourceHealthEntity row = previous != null ? previous : new SourceHealthEntity();
        row.setSource(source);
        row.setStatus(status);
        row.setLastSeenAt(lastReceivedAt);
        row.setEventsLast5m(eventsLast5m);
        row.setEventsLast1h(eventsLast1h);
        row.setDuplicateRate(duplicateRate);
        row.setLateEventRate(lateRate);
        row.setNullRate(nullRate);
        row.setHealthScore(healthScore);
        row.setUpdatedAt(now);
        sourceHealthRepository.save(row);

        if (stale && !wasStale) {
            return Optional.of(staleAlert(source, latest.getEventType(), lastReceivedAt,
                    collector.orElse(null), staleAfter, basis, status, healthScore));
        }
        return Optional.empty();
    }

    private DraftAlert staleAlert(String source,
                                  String eventType,
                                  Instant lastReceivedAt,
                                  CollectorStateEntity collector,
                                  Duration staleAfter,
                                  String basis,
                                  String status,
                                  double healthScore) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("source", source);
        evidence.put("last_seen_at", lastReceivedAt.toString());
        evidence.put("freshness_basis", basis);
        evidence.put("stale_after", staleAfter.toString());
        evidence.put("status", status);
        evidence.put("health_score", healthScore);
        String message = "Source " + source + " is stale; last received at " + lastReceivedAt;
        if (collector != null) {
            Instant lastPollSuccess = collector.getLastPollSuccess();
            evidence.put("last_poll_success_at", lastPollSuccess == null ? null : lastPollSuccess.toString());
            message = "Source " + source + " is stale; collector has not polled successfully since "
                    + lastPollSuccess + " (last received at " + lastReceivedAt + ")";
        }
        return new DraftAlert(
                AlertType.STALE_SOURCE,
                Severity.WARN,
                source,
                eventType,
                null,
                message,
                evidence
        );
    }

    private DraftAlert collectorNeverSucceededAlert(String source,
                                                   Instant lastSeenAt,
                                                   CollectorStateEntity collector,
                                                   Duration staleAfter,
                                                   String status,
                                                   double healthScore) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("source", source);
        evidence.put("last_seen_at", lastSeenAt.toString());
        evidence.put("freshness_basis", "collector_never_succeeded");
        evidence.put("stale_after", staleAfter.toString());
        evidence.put("status", status);
        evidence.put("health_score", healthScore);
        evidence.put("collector_status", collector.getStatus());
        evidence.put("consecutive_failures", collector.getConsecutiveFailures());
        if (collector.getLastError() != null) {
            evidence.put("last_error", collector.getLastError());
        }
        String message = "Collector for source " + source + " has never succeeded and is failing (status="
                + collector.getStatus() + ", consecutive_failures=" + collector.getConsecutiveFailures() + ")";
        return new DraftAlert(
                AlertType.STALE_SOURCE,
                Severity.WARN,
                source,
                "collector",
                null,
                message,
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
