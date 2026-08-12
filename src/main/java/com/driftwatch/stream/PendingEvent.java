package com.driftwatch.stream;

import com.driftwatch.event.DataEvent;
import com.driftwatch.quality.AlertType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable accumulator flowing between topology processors. Built by the ENRICH processor,
 * mutated by the detector processors, materialized by the FINALIZE processor. Never crosses a
 * serialization boundary in this topology (no repartition between processors), so it need not
 * be serializable.
 */
final class PendingEvent {

    final DataEvent event;
    final String payloadHash;
    final Instant receivedAt;
    final List<ProcessedEvent.ProcessedAlert> alerts = new ArrayList<>();

    PendingEvent(DataEvent event, String payloadHash, Instant receivedAt) {
        this.event = event;
        this.payloadHash = payloadHash;
        this.receivedAt = receivedAt;
    }

    /** Status precedence mirrors the legacy {@code QualityProcessor.qualityStatusFor}. */
    String qualityStatus() {
        if (alerts.isEmpty()) return "OK";
        if (alerts.stream().anyMatch(a -> a.type() == AlertType.DUPLICATE_EVENT)) return "DUPLICATE";
        if (alerts.stream().anyMatch(a -> a.type() == AlertType.LATE_EVENT)) return "LATE";
        return "FLAGGED";
    }

    ProcessedEvent toProcessed() {
        return new ProcessedEvent(event, payloadHash, receivedAt, qualityStatus(), List.copyOf(alerts));
    }
}
