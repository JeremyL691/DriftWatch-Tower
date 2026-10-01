package com.driftwatch.stream;

import com.driftwatch.event.DataEvent;
import com.driftwatch.event.RawEnvelope;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.RuleVersions;

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

    final RawEnvelope envelope;
    final String payloadHash;
    final List<ProcessedEvent.ProcessedAlert> alerts = new ArrayList<>();
    WindowEvaluation evaluation;
    /** APPLIED when an active schema baseline governed the null checks, PENDING when none exists. */
    String baselineStatus;
    /**
     * Set when the delivery identity was already processed (redelivery, or a reused ingestion id
     * with different content). Detection must not run again, so business counters stay stable.
     */
    boolean skipDetection;

    PendingEvent(RawEnvelope envelope, String payloadHash) {
        this.envelope = envelope;
        this.payloadHash = payloadHash;
    }

    DataEvent event() {
        return envelope.event();
    }

    Instant receivedAt() {
        return envelope.receivedAt();
    }

    /** Status precedence mirrors the legacy {@code QualityProcessor.qualityStatusFor}. */
    String qualityStatus() {
        if (alerts.isEmpty()) return "OK";
        if (alerts.stream().anyMatch(a -> a.type() == AlertType.DUPLICATE_EVENT)) return "DUPLICATE";
        if (alerts.stream().anyMatch(a -> a.type() == AlertType.LATE_EVENT)) return "LATE";
        return "FLAGGED";
    }

    ProcessedEvent toProcessed() {
        return new ProcessedEvent(
                envelope.event(),
                envelope.ingestionId(),
                envelope.receivedAt(),
                envelope.origin().name(),
                envelope.mode().name(),
                payloadHash,
                qualityStatus(),
                RuleVersions.RULES_VERSION,
                baselineStatus,
                evaluation,
                List.copyOf(alerts));
    }
}