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
    private final com.driftwatch.quality.schema.StreamsBaselineStatus streamsBaselineStatus;

    public SchemaController(SchemaVersionRepository repository,
                            SchemaObservationService schemaObservationService,
                            BaselineOutboxRelay outboxRelay,
                            BaselineOutboxRepository outboxRepository) {
        this(repository, schemaObservationService, outboxRelay, outboxRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SchemaController(SchemaVersionRepository repository,
                            SchemaObservationService schemaObservationService,
                            BaselineOutboxRelay outboxRelay,
                            BaselineOutboxRepository outboxRepository,
                            com.driftwatch.quality.schema.StreamsBaselineStatus streamsBaselineStatus) {
        this.repository = repository;
        this.schemaObservationService = schemaObservationService;
        this.outboxRelay = outboxRelay;
        this.outboxRepository = outboxRepository;
        this.streamsBaselineStatus = streamsBaselineStatus;
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

    /** Publication is durable evidence, not an acknowledgement from every Streams consumer. */
    @GetMapping("/{eventType}/baseline")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> baselineStatus(@PathVariable String eventType) {
        return repository.findFirstByEventTypeAndStatus(eventType, "ACTIVE").map(active -> {
            var outbox = outboxRepository.findFirstByEventTypeAndVersionIdOrderByIdDesc(eventType, active.getId());
            Map<String, Object> sync = new java.util.LinkedHashMap<>();
            String state = outbox.map(row -> "SENT".equals(row.getStatus()) ? "PUBLISHED" : row.getStatus()).orElse("UNKNOWN");
            sync.put("state", state);
            var checkResult = streamsBaselineStatus != null
                    ? streamsBaselineStatus.check(eventType, active.getId())
                    : new com.driftwatch.quality.schema.StreamsBaselineStatus.CheckResult("UNCONFIRMED", null, "Inspector not configured");
            sync.put("consumer_application", checkResult.status());
            if (checkResult.appliedVersionId() != null) {
                sync.put("consumer_applied_version_id", checkResult.appliedVersionId());
            }
            if (checkResult.detail() != null) {
                sync.put("consumer_detail", checkResult.detail());
            }
            sync.put("outbox_id", outbox.map(com.driftwatch.persistence.BaselineOutboxEntity::getId).orElse(null));
            sync.put("published_at", outbox.map(com.driftwatch.persistence.BaselineOutboxEntity::getSentAt).orElse(null));
            return ResponseEntity.ok(Map.<String, Object>of("event_type", eventType, "version_id", active.getId(), "baseline_sync", sync));
        }).orElseGet(() -> ResponseEntity.notFound().build());
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
        var outboxRow = outboxRepository.findFirstByEventTypeAndVersionIdOrderByIdDesc(eventType, activated.getId());
        boolean published = outboxRow.map(row -> "SENT".equals(row.getStatus())
                || outboxRelay.publishNow(row.getId())).orElse(false);
        String syncState = published ? "PUBLISHED" : outboxRow.map(row -> row.getStatus()).orElse("UNKNOWN");
        var checkResult = streamsBaselineStatus != null
                ? streamsBaselineStatus.check(eventType, activated.getId())
                : new com.driftwatch.quality.schema.StreamsBaselineStatus.CheckResult("UNCONFIRMED", null, "consumer application is unconfirmed");
        Map<String, Object> syncMap = new java.util.LinkedHashMap<>();
        syncMap.put("state", syncState);
        syncMap.put("consumer_application", checkResult.status());
        if (checkResult.appliedVersionId() != null) {
            syncMap.put("consumer_applied_version_id", checkResult.appliedVersionId());
        }
        syncMap.put("detail", published
                ? "published to schema-baselines-v1; " + checkResult.detail()
                : "publication is not confirmed; re-query baseline status; " + checkResult.detail());
        return ResponseEntity.ok(Map.of(
                "event_type", eventType,
                "version_id", activated.getId(),
                "status", activated.getStatus(),
                "baseline_sync", syncMap));
    }
}
