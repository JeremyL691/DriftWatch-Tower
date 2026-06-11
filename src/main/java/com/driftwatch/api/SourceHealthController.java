package com.driftwatch.api;

import com.driftwatch.source.SourceHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sources")
public class SourceHealthController {

    private final SourceHealthService service;

    public SourceHealthController(SourceHealthService service) {
        this.service = service;
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
