package com.driftwatch.api;

import com.driftwatch.persistence.MetricWindowRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricWindowController {

    private final MetricWindowRepository repository;

    public MetricWindowController(MetricWindowRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/windows")
    public List<MetricWindowResponse> list(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String metricName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return repository.search(source, eventType, metricName, PageRequest.of(page, size))
                .getContent()
                .stream()
                .map(MetricWindowResponse::from)
                .toList();
    }
}
