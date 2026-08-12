package com.driftwatch.stream;

import com.driftwatch.event.DataEvent;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Canonical value on the {@code quality-events} topic — the outcome of running one event
 * through the quality topology. Persisted by {@link QualityEventSink}.
 */
public record ProcessedEvent(
        DataEvent event,
        String payloadHash,
        Instant receivedAt,
        String qualityStatus,
        List<ProcessedAlert> alerts
) {

    /** Detector output carried over the topic; mirrors {@link com.driftwatch.quality.DraftAlert}. */
    public record ProcessedAlert(
            AlertType type,
            Severity severity,
            String source,
            String eventType,
            String fieldPath,
            String message,
            JsonNode evidence
    ) {}
}
