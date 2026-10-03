package com.driftwatch.stream;

import java.time.Instant;

/**
 * Event-time window decisions shared by the spike processors (execution guide, section 5.1).
 *
 * <ul>
 *   <li>Windows are strictly {@code [start, end)} aligned to UTC epoch multiples.</li>
 *   <li>An input whose {@code windowEnd + grace} is behind the scope watermark is EXPIRED and
 *       must not modify an already-closed window.</li>
 *   <li>An input further in the future than the configured tolerance is FUTURE; it must not
 *       advance the scope watermark.</li>
 * </ul>
 */
final class WindowPolicy {

    private WindowPolicy() {
    }

    static long floorWindow(long epochMs, long windowSizeMs) {
        return epochMs - Math.floorMod(epochMs, windowSizeMs);
    }

    static WindowEvaluation.Outcome classify(long eventTimestampMs,
                                             long windowSizeMs,
                                             long graceMs,
                                             long futureToleranceMs,
                                             long watermarkMs,
                                             Instant receivedAt) {
        if (eventTimestampMs > receivedAt.toEpochMilli() + futureToleranceMs) {
            return WindowEvaluation.Outcome.FUTURE;
        }
        long windowEnd = floorWindow(eventTimestampMs, windowSizeMs) + windowSizeMs;
        if (windowEnd + graceMs < watermarkMs) {
            return WindowEvaluation.Outcome.EXPIRED;
        }
        return WindowEvaluation.Outcome.INCLUDED;
    }

    static WindowEvaluation evaluation(String scope, long windowStartMs, long windowSizeMs,
                                       WindowEvaluation.Outcome outcome, long watermarkMs,
                                       String detail) {
        return new WindowEvaluation(
                scope,
                Instant.ofEpochMilli(windowStartMs),
                Instant.ofEpochMilli(windowStartMs + windowSizeMs),
                outcome,
                watermarkMs == Long.MIN_VALUE ? null : Instant.ofEpochMilli(watermarkMs),
                detail);
    }
}