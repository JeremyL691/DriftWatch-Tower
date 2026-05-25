package com.driftwatch.api;

import com.driftwatch.event.DataEvent;
import com.driftwatch.event.RawEventService;
import com.driftwatch.persistence.RawEventEntity;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final RawEventService service;

    public EventController(RawEventService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EventResponse> ingest(@Valid @RequestBody DataEvent event) {
        RawEventEntity stored = service.ingest(event);
        EventResponse body = EventResponse.from(stored);
        return ResponseEntity.created(URI.create("/events/" + stored.getId())).body(body);
    }

    @GetMapping("/recent")
    public List<EventResponse> recent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.recent(page, size).map(EventResponse::from).getContent();
    }
}
