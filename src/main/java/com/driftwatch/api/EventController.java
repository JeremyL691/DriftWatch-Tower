package com.driftwatch.api;

import com.driftwatch.event.DataEvent;
import com.driftwatch.event.RawEventProducer;
import com.driftwatch.event.RawEventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/events")
public class EventController {

    private final RawEventProducer producer;
    private final RawEventService service;

    public EventController(RawEventProducer producer, RawEventService service) {
        this.producer = producer;
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody DataEvent event) {
        producer.publish(event);
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "event_id", event.eventId()
        ));
    }

    @GetMapping("/recent")
    public List<EventResponse> recent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.recent(page, size).map(EventResponse::from).getContent();
    }
}
