package com.driftwatch.api;

import com.driftwatch.event.DataEvent;
import com.driftwatch.event.IngestionService;
import com.driftwatch.event.RawEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingest and query API (execution guide, section 4.2). A successful ingest answers 202 only
 * after the broker acknowledged the record; the response keeps the historical {@code status}
 * and {@code event_id} fields and adds the internal {@code ingestion_id}.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private static final int MAX_PAGE_SIZE = 100;

    private final IngestionService ingestionService;
    private final RawEventService service;
    private final com.driftwatch.persistence.RawEventRepository rawEventRepository;
    private final com.driftwatch.persistence.QualityAlertRepository alertRepository;

    public EventController(IngestionService ingestionService,
                           RawEventService service,
                           com.driftwatch.persistence.RawEventRepository rawEventRepository,
                           com.driftwatch.persistence.QualityAlertRepository alertRepository) {
        this.ingestionService = ingestionService;
        this.service = service;
        this.rawEventRepository = rawEventRepository;
        this.alertRepository = alertRepository;
    }

    @Operation(summary = "Ingest one data event",
            description = "Validates the event, publishes it to raw-events-v1 and answers 202 only after "
                    + "the broker acknowledged the record. An admin account or an ingest bearer token may call it.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted and confirmed by the broker"),
            @ApiResponse(responseCode = "400", description = "Validation failed; nothing was published"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials"),
            @ApiResponse(responseCode = "409", description = "Idempotency-Key reused with different content"),
            @ApiResponse(responseCode = "503", description = "Broker acknowledgement timed out; retry with the same key")
    })
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DataEvent event) {
        IngestionService.Acceptance acceptance = ingestionService.ingest(event, idempotencyKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", acceptance.replayed() ? "already_accepted" : "accepted");
        body.put("event_id", acceptance.eventId());
        body.put("ingestion_id", acceptance.ingestionId());
        if (acceptance.replayed() && !acceptance.confirmed()) {
            body.put("hint", "a previous attempt reserved this identity but did not confirm; retry the same request");
        }
        return ResponseEntity.accepted().body(body);
    }

    @Operation(summary = "Ingest a batch of data events",
            description = "Up to 100 events and 4 MiB are validated individually before anything is published.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "All items accepted and confirmed by the broker"),
            @ApiResponse(responseCode = "207", description = "Some items were accepted, others failed"),
            @ApiResponse(responseCode = "400", description = "Validation failed; nothing was published"),
            @ApiResponse(responseCode = "409", description = "Idempotency-Key reused with different content"),
            @ApiResponse(responseCode = "503", description = "No item was accepted")
    })
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody List<@Valid DataEvent> events) {
        IngestionService.BatchOutcome outcome = ingestionService.ingestBatch(events, idempotencyKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batch_id", outcome.batchId());
        body.put("count", events.size());
        body.put("accepted", outcome.accepted().stream().map(acceptance -> Map.of(
                "event_id", acceptance.eventId(),
                "ingestion_id", acceptance.ingestionId())).toList());
        body.put("failed_indexes", outcome.failedIndexes());
        if (outcome.allFailed()) {
            body.put("status", "unconfirmed");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        body.put("status", outcome.failedIndexes().isEmpty() ? "accepted" : "partially_accepted");
        return ResponseEntity.status(outcome.failedIndexes().isEmpty() ? HttpStatus.ACCEPTED
                : HttpStatus.MULTI_STATUS).body(body);
    }

    /**
     * One ingestion with its detection evidence and window evaluation (guide 4.5). The business
     * event id is not unique, so the internal ingestion id is the lookup key.
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "Fetch one ingestion by ingestion id")
    @GetMapping("/{ingestionId}")
    public ResponseEntity<Map<String, Object>> byIngestionId(@PathVariable String ingestionId) {
        return rawEventRepository.findByIngestionId(ingestionId)
                .map(raw -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("ingestion_id", raw.getIngestionId());
                    body.put("event_id", raw.getEventId());
                    body.put("source", raw.getSource());
                    body.put("event_type", raw.getEventType());
                    body.put("event_timestamp", raw.getEventTimestamp());
                    body.put("received_at", raw.getReceivedAt());
                    body.put("quality_status", raw.getQualityStatus());
                    body.put("origin", raw.getOrigin());
                    body.put("mode", raw.getMode());
                    body.put("payload", raw.getPayloadJson());
                    body.put("baseline_status", raw.getBaselineStatus());
                    body.put("window_evaluation", raw.getWindowEvaluation());
                    body.put("alerts", alertRepository.findByIngestionIdOrderByCreatedAtAsc(ingestionId).stream()
                            .map(AlertResponse::from).toList());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Evaluation coverage over a recent window (guide 5.3). "OK" must not be read as "every
     * window participated": this reports how much traffic was INCLUDED, how much was excluded and
     * why, and how much ran with no active schema baseline (PENDING), which is the visible symptom
     * of a missing baseline cache.
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "Evaluation coverage over a recent window")
    @GetMapping("/coverage")
    public Map<String, Object> coverage(
            @RequestParam(defaultValue = "24") int hours) {
        if (hours < 1 || hours > 24 * 30) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "hours must be between 1 and 720");
        }
        java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofHours(hours));
        Map<String, Object> row = rawEventRepository.evaluationCoverage(cutoff);
        long total = number(row, "total");
        long included = number(row, "included");
        long expired = number(row, "expired");
        long future = number(row, "future");
        long skippedMode = number(row, "skipped_mode");
        long missing = number(row, "unevaluated_missing");
        long baselineApplied = number(row, "baseline_applied");
        long baselinePending = number(row, "baseline_pending");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("window_hours", hours);
        body.put("since", cutoff);
        body.put("total", total);
        body.put("included", included);
        body.put("excluded", Map.of(
                "expired", expired,
                "future", future,
                "skipped_mode", skippedMode,
                "no_window_evaluation", missing));
        body.put("excluded_total", expired + future + skippedMode + missing);
        body.put("included_ratio", total == 0 ? null : round((double) included / total));
        body.put("baseline", Map.of("applied", baselineApplied, "pending", baselinePending));
        body.put("note", "INCLUDED events participated in window evaluation; the rest are recorded "
                + "exclusions and must not be read as evaluated. baseline.pending counts events whose "
                + "baseline-dependent checks were skipped because no ACTIVE baseline was available.");
        return body;
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    @GetMapping("/recent")
    public List<EventResponse> recent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        List<EventResponse> rows = new ArrayList<>();
        service.recent(page, size).forEach(entity -> rows.add(EventResponse.from(entity)));
        return rows;
    }
}