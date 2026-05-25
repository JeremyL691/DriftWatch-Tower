package com.driftwatch.api;

import com.driftwatch.persistence.RawEventEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record EventResponse(
        Long id,
        @JsonProperty("event_id") String eventId,
        String source,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("event_timestamp") Instant eventTimestamp,
        @JsonProperty("received_at") Instant receivedAt,
        JsonNode payload,
        @JsonProperty("payload_hash") String payloadHash,
        @JsonProperty("quality_status") String qualityStatus
) {
    public static EventResponse from(RawEventEntity e) {
        return new EventResponse(
                e.getId(),
                e.getEventId(),
                e.getSource(),
                e.getEventType(),
                e.getEventTimestamp(),
                e.getReceivedAt(),
                e.getPayloadJson(),
                e.getPayloadHash(),
                e.getQualityStatus()
        );
    }
}
