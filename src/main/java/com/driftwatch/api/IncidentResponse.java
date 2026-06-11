package com.driftwatch.api;

import com.driftwatch.persistence.AlertIncidentEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record IncidentResponse(
        Long id,
        String title,
        String description,
        String status,
        String source,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("resolved_at") Instant resolvedAt
) {
    public static IncidentResponse from(AlertIncidentEntity e) {
        return new IncidentResponse(
                e.getId(), e.getTitle(), e.getDescription(),
                e.getStatus(), e.getSource(), e.getEventType(),
                e.getCreatedAt(), e.getResolvedAt()
        );
    }
}
