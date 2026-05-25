package com.driftwatch.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

public record DataEvent(
        @NotBlank @JsonProperty("event_id") String eventId,
        @NotBlank String source,
        @NotBlank @JsonProperty("event_type") String eventType,
        @NotNull  @JsonProperty("event_timestamp") Instant eventTimestamp,
        @NotNull  Map<String, Object> payload
) {}
