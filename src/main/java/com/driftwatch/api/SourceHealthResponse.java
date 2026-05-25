package com.driftwatch.api;

import com.driftwatch.persistence.SourceHealthEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record SourceHealthResponse(
        String source,
        String status,
        @JsonProperty("last_seen_at") Instant lastSeenAt,
        @JsonProperty("events_last_5m") long eventsLast5m,
        @JsonProperty("events_last_1h") long eventsLast1h,
        @JsonProperty("duplicate_rate") double duplicateRate,
        @JsonProperty("late_event_rate") double lateEventRate,
        @JsonProperty("null_rate") double nullRate,
        @JsonProperty("health_score") double healthScore,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static SourceHealthResponse from(SourceHealthEntity e) {
        return new SourceHealthResponse(
                e.getSource(),
                e.getStatus(),
                e.getLastSeenAt(),
                e.getEventsLast5m(),
                e.getEventsLast1h(),
                e.getDuplicateRate(),
                e.getLateEventRate(),
                e.getNullRate(),
                e.getHealthScore(),
                e.getUpdatedAt()
        );
    }
}
