package com.driftwatch.api;

import com.driftwatch.event.DataEvent;
import com.driftwatch.event.RawEventProducer;
import com.driftwatch.event.RawEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final RawEventProducer producer;
    private final RawEventService service;

    public EventController(RawEventProducer producer, RawEventService service) {
        this.producer = producer;
        this.service = service;
    }

    @Operation(summary = "Ingest one data event",
            description = "Validates the event, publishes it to the raw topic and answers 202 only after "
                    + "the broker acknowledged the record. An admin account or an ingest bearer token may call it.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted and confirmed by the broker"),
            @ApiResponse(responseCode = "400", description = "Validation failed; nothing was published"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    })
    @PostMapping
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody DataEvent event) {
        producer.publish(event);
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "event_id", event.eventId()
        ));
    }

    @Operation(summary = "Ingest a batch of data events",
            description = "Up to 100 events are validated individually before any publish; the response keeps "
                    + "the batch id and count and adds accepted/failed indexes.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted and confirmed by the broker"),
            @ApiResponse(responseCode = "400", description = "Validation failed; nothing was published"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials")
    })
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(@Valid @RequestBody List<@Valid DataEvent> events) {
        String batchId = UUID.randomUUID().toString();
        for (DataEvent event : events) {
            producer.publish(event);
        }
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "batch_id", batchId,
                "count", events.size()
        ));
    }

    @GetMapping("/recent")
    public List<EventResponse> recent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.recent(page, size).map(EventResponse::from).getContent();
    }
}
