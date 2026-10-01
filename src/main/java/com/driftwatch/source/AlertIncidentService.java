package com.driftwatch.source;

import com.driftwatch.persistence.AlertIncidentEntity;
import com.driftwatch.persistence.AlertIncidentRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.quality.Severity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Incident correlation and lifecycle (execution guide, section 7.2).
 *
 * <p>Non-INFO alerts correlate to the most recent OPEN incident for the same source/event_type
 * created within the correlation window; INFO alerts stay in the alert list so duplicate notices
 * cannot flood the incident view. A PostgreSQL advisory transaction lock serialises correlation
 * per scope, so concurrent alerts cannot create two incidents for the same range, and each alert
 * is linked at most once.
 */
@Service
public class AlertIncidentService {

    static final Duration CORRELATION_WINDOW = Duration.ofMinutes(5);

    private final AlertIncidentRepository incidentRepository;
    private final QualityAlertRepository alertRepository;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public AlertIncidentService(AlertIncidentRepository incidentRepository,
                                QualityAlertRepository alertRepository,
                                Clock clock) {
        this.incidentRepository = incidentRepository;
        this.alertRepository = alertRepository;
        this.clock = clock;
    }

    /** Correlates one persisted alert; returns empty for INFO alerts. */
    @Transactional
    public Optional<AlertIncidentEntity> correlate(QualityAlertEntity alert) {
        if (alert.getSeverity() == Severity.INFO) {
            return Optional.empty();
        }
        if (alert.getIncidentId() != null) {
            return incidentRepository.findById(alert.getIncidentId());
        }
        lockScope(alert.getSource(), alert.getEventType());
        Instant now = clock.instant();
        AlertIncidentEntity incident = incidentRepository
                .findFirstBySourceAndEventTypeAndStatusOrderByCreatedAtDesc(
                        alert.getSource(), alert.getEventType(), AlertIncidentEntity.STATUS_OPEN)
                .filter(existing -> existing.getCreatedAt().isAfter(now.minus(CORRELATION_WINDOW)))
                .orElseGet(() -> createIncident(alert, now));
        alert.setIncidentId(incident.getId());
        alertRepository.save(alert);
        return Optional.of(incident);
    }

    /** Resolves an incident and every unresolved alert it owns, in one transaction. */
    @Transactional
    public AlertIncidentEntity resolveIncident(long incidentId, String rootCause) {
        AlertIncidentEntity incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("incident " + incidentId + " does not exist"));
        Instant now = clock.instant();
        List<QualityAlertEntity> unresolved = alertRepository.findByIncidentIdAndStatusNot(
                incidentId, QualityAlertEntity.STATUS_RESOLVED);
        for (QualityAlertEntity alert : unresolved) {
            alert.setStatus(QualityAlertEntity.STATUS_RESOLVED);
            alert.setResolvedAt(now);
            if (rootCause != null && !rootCause.isBlank()) {
                alert.setRootCause(rootCause);
            }
            alertRepository.save(alert);
        }
        if (!AlertIncidentEntity.STATUS_RESOLVED.equals(incident.getStatus())) {
            incident.setStatus(AlertIncidentEntity.STATUS_RESOLVED);
            incident.setResolvedAt(now);
            if (rootCause != null && !rootCause.isBlank()) {
                incident.setDescription(rootCause);
            }
            incidentRepository.save(incident);
        }
        return incident;
    }

    /**
     * Resolves an alert and, when that was the last unresolved alert of its incident, resolves the
     * incident as well. A repeated resolve keeps the original timestamp and returns 200.
     */
    @Transactional
    public QualityAlertEntity resolveAlert(long alertId, String rootCause) {
        QualityAlertEntity alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("alert " + alertId + " does not exist"));
        Instant now = clock.instant();
        if (!QualityAlertEntity.STATUS_RESOLVED.equals(alert.getStatus())) {
            alert.setStatus(QualityAlertEntity.STATUS_RESOLVED);
            alert.setResolvedAt(now);
            if (rootCause != null && !rootCause.isBlank()) {
                alert.setRootCause(rootCause);
            }
            alert = alertRepository.save(alert);
        }
        if (alert.getIncidentId() != null) {
            autoResolveIfComplete(alert.getIncidentId(), now);
        }
        return alert;
    }

    /** Acknowledges an alert; repeating the same operation keeps the original timestamp. */
    @Transactional
    public QualityAlertEntity acknowledgeAlert(long alertId, String acknowledgedBy) {
        QualityAlertEntity alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("alert " + alertId + " does not exist"));
        if (QualityAlertEntity.STATUS_RESOLVED.equals(alert.getStatus())) {
            throw new IllegalStateException("a resolved alert cannot be acknowledged");
        }
        if (!QualityAlertEntity.STATUS_ACKNOWLEDGED.equals(alert.getStatus())) {
            alert.setStatus(QualityAlertEntity.STATUS_ACKNOWLEDGED);
            alert.setAcknowledgedBy(acknowledgedBy == null || acknowledgedBy.isBlank()
                    ? "anonymous" : acknowledgedBy);
            alert.setAcknowledgedAt(clock.instant());
            alert = alertRepository.save(alert);
        }
        return alert;
    }

    private void autoResolveIfComplete(Long incidentId, Instant now) {
        long unresolved = alertRepository.countByIncidentIdAndStatusNot(incidentId,
                QualityAlertEntity.STATUS_RESOLVED);
        if (unresolved > 0) {
            return;
        }
        incidentRepository.findById(incidentId).ifPresent(incident -> {
            if (!AlertIncidentEntity.STATUS_RESOLVED.equals(incident.getStatus())) {
                incident.setStatus(AlertIncidentEntity.STATUS_RESOLVED);
                incident.setResolvedAt(now);
                incidentRepository.save(incident);
            }
        });
    }

    private AlertIncidentEntity createIncident(QualityAlertEntity alert, Instant now) {
        AlertIncidentEntity incident = new AlertIncidentEntity();
        incident.setTitle(alert.getAlertType() + " on " + alert.getSource());
        incident.setDescription("auto-correlated from " + alert.getAlertType());
        incident.setSource(alert.getSource());
        incident.setEventType(alert.getEventType());
        incident.setStatus(AlertIncidentEntity.STATUS_OPEN);
        incident.setCreatedAt(now);
        return incidentRepository.save(incident);
    }

    /** Serialises correlation per scope so concurrent alerts share one incident. */
    private void lockScope(String source, String eventType) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key)::bigint)")
                .setParameter("key", source + "|" + eventType)
                .getSingleResult();
    }
}
