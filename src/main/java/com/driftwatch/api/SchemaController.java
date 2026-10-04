package com.driftwatch.api;

import com.driftwatch.persistence.BaselineOutboxRepository;
import com.driftwatch.persistence.SchemaVersionRepository;
import com.driftwatch.quality.schema.BaselineOutboxRelay;
import com.driftwatch.quality.schema.SchemaObservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schemas")
public class SchemaController {

    private final SchemaVersionRepository repository;
    private final SchemaObservationService schemaObservationService;
    private final BaselineOutboxRelay outboxRelay;
    private final BaselineOutboxRepository outboxRepository;

    public SchemaController(SchemaVersionRepository repository,
                            SchemaObservationService schemaObservationService,
                            BaselineOutboxRelay outboxRelay,
                            BaselineOutboxRepository outboxRepository) {
        this.repository = repository;
        this.schemaObservationService = schemaObservationService;
        this.outboxRelay = outboxRelay;
        this.outboxRepository = outboxRepository;
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

    /**
     * Explicitly activates an existing version (guide 4.4). Activation and the outbox row commit
     * together; the response reports whether the compacted baseline topic has been updated yet,
     * so a caller never reads "activated" as "Streams already applied it".
     */
    @PutMapping("/{eventType}/baseline")
    public ResponseEntity<Map<String, Object>> activateBaseline(@PathVariable String eventType,
                                                                @RequestBody Map<String, Object> body) {
        Object rawVersionId = body == null ? null : body.get("version_id");
        if (rawVersionId == null) {
            throw new IllegalArgumentException("version_id is required");
        }
        long versionId;
        try {
            versionId = Long.parseLong(String.valueOf(rawVersionId));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("version_id must be a number");
        }
        var activated = schemaObservationService.activateBaseline(eventType, versionId, Instant.now());
        var outboxRow = outboxRepository.findTop50ByStatusOrderByIdAsc(
                        com.driftwatch.persistence.BaselineOutboxEntity.STATUS_PENDING).stream()
                .filter(row -> row.getVersionId().equals(activated.getId()))
                .findFirst();
        boolean published = outboxRow.map(row -> outboxRelay.publishNow(row.getId())).orElse(false);
        String syncState = published ? "PUBLISHED" : "PENDING";
        return ResponseEntity.ok(Map.of(
                "event_type", eventType,
                "version_id", activated.getId(),
                "status", activated.getStatus(),
                "baseline_sync", Map.of(
                        "state", syncState,
                        "detail", published
                                ? "published to schema-baselines-v1"
                                : "queued in the baseline outbox; Streams has not applied it yet")));
    }
}
