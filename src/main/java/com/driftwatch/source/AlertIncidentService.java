package com.driftwatch.source;

import com.driftwatch.persistence.AlertIncidentEntity;
import com.driftwatch.persistence.AlertIncidentRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AlertIncidentService {

    private static final Duration CORRELATION_WINDOW = Duration.ofMinutes(5);

    private final AlertIncidentRepository incidentRepository;
    private final QualityAlertRepository alertRepository;

    public AlertIncidentService(AlertIncidentRepository incidentRepository,
                                QualityAlertRepository alertRepository) {
        this.incidentRepository = incidentRepository;
        this.alertRepository = alertRepository;
    }

    @Transactional
    public AlertIncidentEntity findOrCreateIncident(QualityAlertEntity alert) {
        Optional<AlertIncidentEntity> existing = incidentRepository
                .findFirstBySourceAndEventTypeAndStatusOrderByCreatedAtDesc(
                        alert.getSource(), alert.getEventType(), "OPEN");

        if (existing.isPresent()) {
            AlertIncidentEntity incident = existing.get();
            if (incident.getCreatedAt().isAfter(Instant.now().minus(CORRELATION_WINDOW))) {
                alert.setIncidentId(incident.getId());
                alertRepository.save(alert);
                return incident;
            }
        }

        AlertIncidentEntity newIncident = new AlertIncidentEntity();
        newIncident.setTitle(alert.getAlertType() + " on " + alert.getSource());
        newIncident.setSource(alert.getSource());
        newIncident.setEventType(alert.getEventType());
        newIncident.setCreatedAt(Instant.now());
        newIncident = incidentRepository.save(newIncident);

        alert.setIncidentId(newIncident.getId());
        alertRepository.save(alert);
        return newIncident;
    }
}
