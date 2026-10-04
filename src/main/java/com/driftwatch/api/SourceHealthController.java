package com.driftwatch.api;

import com.driftwatch.source.CollectorStatusService;
import com.driftwatch.source.SourceHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sources")
public class SourceHealthController {

    private final SourceHealthService service;
    private final CollectorStatusService collectorStatusService;

    public SourceHealthController(SourceHealthService service,
                                  CollectorStatusService collectorStatusService) {
        this.service = service;
        this.collectorStatusService = collectorStatusService;
    }

    /** Collector state, checkpoints, lag, backlog and gaps; a pure read (guide 4.5). */
    @GetMapping("/collectors")
    public List<Map<String, Object>> collectors() {
        return collectorStatusService.collectors();
    }

    @GetMapping("/health")
    public List<SourceHealthResponse> list() {
        return service.list().stream().map(SourceHealthResponse::from).toList();
    }

    @GetMapping("/{source}/health")
    public ResponseEntity<SourceHealthResponse> bySource(@PathVariable String source) {
        return service.get(source)
                .map(SourceHealthResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
