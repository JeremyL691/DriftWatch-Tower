package com.driftwatch.api;

import com.driftwatch.dlt.DeadLetterService;
import com.driftwatch.dlt.DltStage;
import com.driftwatch.persistence.DeadLetterRecordEntity;
import com.driftwatch.persistence.DeadLetterReplayEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dead-letter management API (execution guide, section 4.5). Secrets never appear in these
 * responses: payloads were truncated and scrubbed before they reached Kafka.
 *
 * <p>Invalid filters and paging are rejected with 400 rather than silently adjusted, so an operator
 * never reads a list that is not the one they asked for.
 */
@RestController
@RequestMapping("/api/v1/dead-letters")
public class DeadLetterController {

    private static final Set<String> STAGES = Arrays.stream(DltStage.values())
            .map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> STATES = Set.of(
            DeadLetterRecordEntity.STATE_OPEN,
            DeadLetterRecordEntity.STATE_REPLAYED,
            DeadLetterRecordEntity.STATE_DISCARDED);
    private static final int MAX_PAGE_SIZE = 100;

    private final DeadLetterService service;

    public DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String stage,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        if (stage != null && !STAGES.contains(stage)) {
            throw new IllegalArgumentException("stage must be one of SOURCE, STREAM, SINK");
        }
        if (status != null && !STATES.contains(status)) {
            throw new IllegalArgumentException("status must be one of OPEN, REPLAYED, DISCARDED");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return service.list(stage, status, page, size).stream().map(this::summary).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        DeadLetterRecordEntity record = service.get(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>(summary(record));
        body.put("reason", record.getReason());
        body.put("payload", record.getPayload());
        // Absent Kafka coordinates are JSON null, not the string "null".
        Map<String, Object> kafka = new LinkedHashMap<>();
        kafka.put("topic", record.getKafkaTopic());
        kafka.put("partition", record.getKafkaPartition());
        kafka.put("offset", record.getKafkaOffset());
        body.put("kafka", kafka);
        List<Map<String, Object>> history = service.replayHistory(id).stream()
                .map(this::replayView).toList();
        body.put("replay_history", history);
        return ResponseEntity.ok(body);
    }

    /**
     * Replays one dead letter. An unknown id is 404. A published replay is 202; a replay the broker
     * did not acknowledge is 503, so the caller knows the record is still unrecovered and can retry.
     * Replaying an already REPLAYED record is allowed and reported by its own outcome.
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable long id) {
        if (service.get(id) == null) {
            return ResponseEntity.notFound().build();
        }
        DeadLetterService.ReplayResult result = service.replay(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("replay_attempt_id", result.replayAttemptId());
        body.put("stage", result.stage());
        body.put("outcome", result.outcome());
        body.put("detail", result.detail());
        body.put("dead_letter_id", id);
        HttpStatus status = "PUBLISHED".equals(result.outcome())
                ? HttpStatus.ACCEPTED
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> summary(DeadLetterRecordEntity record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.getId());
        view.put("diagnostic_id", record.getDiagnosticId());
        view.put("stage", record.getStage());
        view.put("status", record.getRecoveryState());
        view.put("ingestion_id", record.getIngestionId());
        view.put("source", record.getSource());
        view.put("event_type", record.getEventType());
        view.put("attempts", record.getAttempts());
        view.put("created_at", record.getCreatedAt());
        view.put("recovered_at", record.getRecoveredAt());
        return view;
    }

    private Map<String, Object> replayView(DeadLetterReplayEntity replay) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("replay_attempt_id", replay.getReplayAttemptId());
        view.put("stage", replay.getStage());
        view.put("outcome", replay.getOutcome());
        view.put("detail", replay.getDetail());
        view.put("requested_at", replay.getRequestedAt());
        return view;
    }
}
