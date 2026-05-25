package com.driftwatch.api;

import com.driftwatch.persistence.SchemaVersionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/schemas")
public class SchemaController {

    private final SchemaVersionRepository repository;

    public SchemaController(SchemaVersionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<SchemaResponse> list() {
        return repository.findAllByOrderByEventTypeAscFirstSeenAtAsc().stream()
                .map(SchemaResponse::from).toList();
    }

    @GetMapping("/{eventType}")
    public List<SchemaResponse> byEventType(@PathVariable String eventType) {
        return repository.findByEventTypeOrderByFirstSeenAtAsc(eventType).stream()
                .map(SchemaResponse::from).toList();
    }
}
