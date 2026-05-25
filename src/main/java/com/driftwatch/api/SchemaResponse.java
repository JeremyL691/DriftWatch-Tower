package com.driftwatch.api;

import com.driftwatch.persistence.SchemaVersionEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record SchemaResponse(
        Long id,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("schema_hash") String schemaHash,
        JsonNode schema,
        @JsonProperty("first_seen_at") Instant firstSeenAt,
        @JsonProperty("last_seen_at") Instant lastSeenAt,
        String status
) {
    public static SchemaResponse from(SchemaVersionEntity e) {
        return new SchemaResponse(e.getId(), e.getEventType(), e.getSchemaHash(),
                e.getSchemaJson(), e.getFirstSeenAt(), e.getLastSeenAt(), e.getStatus());
    }
}
