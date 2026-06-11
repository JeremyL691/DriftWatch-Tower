package com.driftwatch.api;

import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
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

    public AlertController(QualityAlertRepository repository) {
        this.repository = repository;
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

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<AlertResponse> acknowledge(@PathVariable Long id,
                                                     @RequestBody Map<String, String> body) {
        return repository.findById(id).map(alert -> {
            alert.setStatus("ACKNOWLEDGED");
            alert.setAcknowledgedBy(body.getOrDefault("acknowledgedBy", "anonymous"));
            alert.setAcknowledgedAt(Instant.now());
            return ResponseEntity.ok(AlertResponse.from(repository.save(alert)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<AlertResponse> resolve(@PathVariable Long id,
                                                 @RequestBody Map<String, String> body) {
        return repository.findById(id).map(alert -> {
            alert.setStatus("RESOLVED");
            alert.setRootCause(body.getOrDefault("rootCause", ""));
            alert.setResolvedAt(Instant.now());
            return ResponseEntity.ok(AlertResponse.from(repository.save(alert)));
        }).orElse(ResponseEntity.notFound().build());
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
