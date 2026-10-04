package com.driftwatch.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Per-event window decision (execution guide, sections 5.1 and 5.2).
 *
 * <p>An exclusion is recorded here as evidence instead of being treated as a successful
 * measurement: an {@code EXPIRED} or {@code FUTURE} event is persisted with its raw data and a
 * reason, but contributes nothing to a window.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WindowEvaluation(
        @JsonProperty("scope") String scope,
        @JsonProperty("window_start") Instant windowStart,
        @JsonProperty("window_end") Instant windowEnd,
        @JsonProperty("outcome") Outcome outcome,
        /** Scope watermark after this event was evaluated. */
        @JsonProperty("watermark") Instant watermark,
        /** Optional reason such as BASELINE_ZERO or WARMING_UP. */
        @JsonProperty("detail") String detail
) {

    public enum Outcome {
        /** Contributed to its window. */
        INCLUDED,
        /** windowEnd + grace is behind the scope watermark: raw evidence kept, window untouched. */
        EXPIRED,
        /** Further in the future than the tolerance: watermark not advanced, window untouched. */
        FUTURE,
        /** Mode (BOOTSTRAP/REPLAY/SYNTHETIC) does not participate in live windows. */
        SKIPPED_MODE,
        /** This raw record was already processed under the same ingestion id. */
        REDELIVERY,
        /** Same ingestion id arrived with different content: a failure path, never re-counted. */
        CONFLICT
    }

    public static WindowEvaluation notApplicable(String scope, Outcome outcome, String detail) {
        return new WindowEvaluation(scope, null, null, outcome, null, detail);
    }
}