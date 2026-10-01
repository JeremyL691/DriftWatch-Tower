package com.driftwatch.stream;

import com.driftwatch.event.DataEvent;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Canonical value on the {@code quality-events} topic — the outcome of running one event
 * through the quality topology. Persisted by {@link QualityEventSink}.
 *
 * <p>Carries the internal identity ({@code ingestionId}), the first-received time, the window
 * evaluation and the rule version so the sink, the API and the evidence stay consistent with
 * the execution guide, section 4.1.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessedEvent(
        DataEvent event,
        @JsonProperty("ingestion_id") UUID ingestionId,
        @JsonProperty("received_at") Instant receivedAt,
        @JsonProperty("origin") String origin,
        @JsonProperty("mode") String mode,
        @JsonProperty("payload_hash") String payloadHash,
        @JsonProperty("quality_status") String qualityStatus,
        @JsonProperty("rule_version") String ruleVersion,
        /** APPLIED/PENDING: whether an active schema baseline governed baseline-dependent checks. */
        @JsonProperty("baseline_status") String baselineStatus,
        @JsonProperty("window_evaluation") WindowEvaluation windowEvaluation,
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