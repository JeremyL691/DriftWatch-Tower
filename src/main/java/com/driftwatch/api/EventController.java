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

    public EventController(IngestionService ingestionService, RawEventService service) {
        this.ingestionService = ingestionService;
        this.service = service;
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