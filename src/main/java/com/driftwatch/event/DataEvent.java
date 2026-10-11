package com.driftwatch.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/**
 * One ingested business event. The length limits mirror the raw_events columns (event_id
 * VARCHAR(128), source and event_type VARCHAR(64)) so an oversized value is rejected with 400
 * before anything is published, instead of failing later in the sink.
 */
public record DataEvent(
        @NotBlank @Size(max = 128) @JsonProperty("event_id") String eventId,
        @NotBlank @Size(max = 64) String source,
        @NotBlank @Size(max = 64) @JsonProperty("event_type") String eventType,
        @NotNull  @JsonProperty("event_timestamp") Instant eventTimestamp,
        @NotNull  Map<String, Object> payload
) {}
