package com.driftwatch.api;

import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.source.AlertIncidentService;
import com.driftwatch.quality.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final QualityAlertRepository repository;
    private final AlertIncidentService incidentService;

    public AlertController(QualityAlertRepository repository, AlertIncidentService incidentService) {
        this.repository = repository;
        this.incidentService = incidentService;
    }

    @GetMapping
    public List<AlertResponse> list(
            @RequestParam(required = false) AlertType type,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<QualityAlertEntity> result;
        if (status != null && !status.isBlank()) {
            result = repository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else if (type != null && source != null && !source.isBlank()) {
            result = repository.findByAlertTypeAndSourceOrderByCreatedAtDesc(type, source, pageable);
        } else if (type != null) {
            result = repository.findByAlertTypeOrderByCreatedAtDesc(type, pageable);
        } else if (source != null && !source.isBlank()) {
            result = repository.findBySourceOrderByCreatedAtDesc(source, pageable);
        } else {
            result = repository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return result.getContent().stream()
                .map(AlertResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlertResponse> get(@PathVariable Long id) {
        return repository.findById(id)
                .map(AlertResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Idempotent: repeating the same acknowledge keeps the original timestamp. */
    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<AlertResponse> acknowledge(@PathVariable Long id,
                                                     @RequestBody(required = false) Map<String, String> body) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String by = body == null ? null : body.get("acknowledgedBy");
            return ResponseEntity.ok(AlertResponse.from(incidentService.acknowledgeAlert(id, by)));
        } catch (IllegalStateException illegalTransition) {
            return ResponseEntity.status(409).build();
        }
    }

    /** Resolving the last unresolved alert of an incident also resolves the incident. */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<AlertResponse> resolve(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, String> body) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        String rootCause = body == null ? null : body.get("rootCause");
        return ResponseEntity.ok(AlertResponse.from(incidentService.resolveAlert(id, rootCause)));
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return Map.of(
                "total", repository.count(),
                "open", repository.countByStatus("OPEN"),
                "acknowledged", repository.countByStatus("ACKNOWLEDGED"),
                "resolved", repository.countByStatus("RESOLVED")
        );
    }
}
