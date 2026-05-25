package com.driftwatch.quality;

import com.fasterxml.jackson.databind.JsonNode;

/** Detector output, persisted by QualityProcessor. */
public record DraftAlert(
        AlertType type,
        Severity severity,
        String source,
        String eventType,
        String fieldPath,
        String message,
        JsonNode evidence
) {}
