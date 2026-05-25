package com.driftwatch.quality.window;

import java.time.Instant;
import java.util.List;

public interface MetricWindowRecorder {

    MetricUpdate increment(String source, String eventType, String metricName, Instant observedAt, double delta);

    MetricUpdate set(String source, String eventType, String metricName, Instant observedAt, double value);

    List<MetricSnapshot> recentCompleted(String source, String eventType, String metricName, Instant observedAt, int limit);

    record MetricUpdate(
            Instant windowStart,
            Instant windowEnd,
            double previousValue,
            double currentValue
    ) {}

    record MetricSnapshot(
            Instant windowStart,
            Instant windowEnd,
            double value
    ) {}
}
