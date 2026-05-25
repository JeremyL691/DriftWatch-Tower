package com.driftwatch.api;

import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.quality.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final QualityAlertRepository repository;

    public AlertController(QualityAlertRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<AlertResponse> list(
            @RequestParam(required = false) AlertType type,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<?> result;
        if (type != null && source != null && !source.isBlank()) {
            result = repository.findByAlertTypeAndSourceOrderByCreatedAtDesc(type, source, pageable);
        } else if (type != null) {
            result = repository.findByAlertTypeOrderByCreatedAtDesc(type, pageable);
        } else if (source != null && !source.isBlank()) {
            result = repository.findBySourceOrderByCreatedAtDesc(source, pageable);
        } else {
            result = repository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return result.getContent().stream()
                .map(e -> AlertResponse.from((com.driftwatch.persistence.QualityAlertEntity) e))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlertResponse> get(@PathVariable Long id) {
        return repository.findById(id)
                .map(AlertResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
