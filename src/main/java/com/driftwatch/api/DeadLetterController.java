package com.driftwatch.api;

import com.driftwatch.dlt.DeadLetterService;
import com.driftwatch.persistence.DeadLetterRecordEntity;
import com.driftwatch.persistence.DeadLetterReplayEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dead-letter management API (execution guide, section 4.5). Secrets never appear in these
 * responses: payloads were truncated and scrubbed before they reached Kafka.
 */
@RestController
@RequestMapping("/api/v1/dead-letters")
public class DeadLetterController {

    private final DeadLetterService service;

    public DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String stage,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
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
        body.put("kafka", Map.of(
                "topic", String.valueOf(record.getKafkaTopic()),
                "partition", String.valueOf(record.getKafkaPartition()),
                "offset", String.valueOf(record.getKafkaOffset())));
        List<Map<String, Object>> history = service.replayHistory(id).stream()
                .map(this::replayView).toList();
        body.put("replay_history", history);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable long id) {
        DeadLetterService.ReplayResult result = service.replay(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("replay_attempt_id", result.replayAttemptId());
        body.put("stage", result.stage());
        body.put("outcome", result.outcome());
        body.put("detail", result.detail());
        body.put("dead_letter_id", id);
        return ResponseEntity.accepted().body(body);
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
