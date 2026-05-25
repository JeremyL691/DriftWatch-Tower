package com.driftwatch.api;

import com.driftwatch.persistence.MetricWindowEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record MetricWindowResponse(
        Long id,
        String source,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("window_start") Instant windowStart,
        @JsonProperty("window_end") Instant windowEnd,
        @JsonProperty("metric_name") String metricName,
        @JsonProperty("metric_value") double metricValue
) {
    public static MetricWindowResponse from(MetricWindowEntity e) {
        return new MetricWindowResponse(
                e.getId(),
                e.getSource(),
                e.getEventType(),
                e.getWindowStart(),
                e.getWindowEnd(),
                e.getMetricName(),
                e.getMetricValue()
        );
    }
}
