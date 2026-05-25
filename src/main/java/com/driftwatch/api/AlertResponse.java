package com.driftwatch.api;

import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record AlertResponse(
        Long id,
        @JsonProperty("alert_type") AlertType alertType,
        Severity severity,
        String source,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("field_path") String fieldPath,
        String message,
        JsonNode evidence,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("resolved_at") Instant resolvedAt
) {
    public static AlertResponse from(QualityAlertEntity e) {
        return new AlertResponse(
                e.getId(), e.getAlertType(), e.getSeverity(),
                e.getSource(), e.getEventType(), e.getFieldPath(),
                e.getMessage(), e.getEvidenceJson(),
                e.getCreatedAt(), e.getResolvedAt()
        );
    }
}
