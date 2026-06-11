package com.driftwatch.api;

import com.driftwatch.persistence.AlertIncidentEntity;
import com.driftwatch.persistence.AlertIncidentRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.source.AlertIncidentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final AlertIncidentRepository incidentRepository;
    private final QualityAlertRepository alertRepository;
    private final AlertIncidentService incidentService;

    public IncidentController(AlertIncidentRepository incidentRepository,
                              QualityAlertRepository alertRepository,
                              AlertIncidentService incidentService) {
        this.incidentRepository = incidentRepository;
        this.alertRepository = alertRepository;
        this.incidentService = incidentService;
    }

    @GetMapping
    public List<IncidentResponse> list() {
        return incidentRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(IncidentResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<IncidentResponse> get(@PathVariable Long id) {
        return incidentRepository.findById(id)
                .map(IncidentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<IncidentResponse> resolve(@PathVariable Long id) {
        return incidentRepository.findById(id).map(incident -> {
            incident.setStatus("RESOLVED");
            incident.setResolvedAt(Instant.now());
            return ResponseEntity.ok(IncidentResponse.from(incidentRepository.save(incident)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return Map.of(
                "total", incidentRepository.count(),
                "open", incidentRepository.countByStatus("OPEN"),
                "resolved", incidentRepository.countByStatus("RESOLVED")
        );
    }
}
