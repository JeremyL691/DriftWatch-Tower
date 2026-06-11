package com.driftwatch.support;

import com.driftwatch.quality.window.MetricWindowRecorder;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryMetricWindowRecorder implements MetricWindowRecorder {

    private final Duration windowSize;
    private final Map<String, Double> values = new HashMap<>();

    public InMemoryMetricWindowRecorder(Duration windowSize) {
        this.windowSize = windowSize;
    }

    @Override
    public MetricUpdate increment(String source, String eventType, String metricName, Instant observedAt, double delta) {
        Window window = windowFor(observedAt);
        String key = key(source, eventType, metricName, window.start());
        double previous = values.getOrDefault(key, 0.0d);
        double current = previous + delta;
        values.put(key, current);
        return new MetricUpdate(window.start(), window.end(), previous, current);
    }

    @Override
    public MetricUpdate set(String source, String eventType, String metricName, Instant observedAt, double value) {
        Window window = windowFor(observedAt);
        String key = key(source, eventType, metricName, window.start());
        double previous = values.getOrDefault(key, 0.0d);
        values.put(key, value);
        return new MetricUpdate(window.start(), window.end(), previous, value);
    }

    @Override
    public List<MetricSnapshot> recentCompleted(String source, String eventType, String metricName, Instant observedAt, int limit) {
        Window current = windowFor(observedAt);
        return values.entrySet().stream()
                .filter(e -> e.getKey().startsWith(source + "|" + eventType + "|" + metricName + "|"))
                .map(e -> snapshot(e.getKey(), e.getValue()))
                .filter(s -> !s.windowEnd().isAfter(current.start()))
                .sorted((a, b) -> b.windowStart().compareTo(a.windowStart()))
                .limit(limit)
                .toList();
    }

    private MetricSnapshot snapshot(String key, double value) {
        String[] parts = key.split("\\|");
        Instant start = Instant.parse(parts[3]);
        return new MetricSnapshot(start, start.plusSeconds(windowSize.toSeconds()), value);
    }

    private Window windowFor(Instant observedAt) {
        long seconds = windowSize.toSeconds();
        long bucket = Math.floorDiv(observedAt.getEpochSecond(), seconds);
        Instant start = Instant.ofEpochSecond(bucket * seconds);
        return new Window(start, start.plusSeconds(seconds));
    }

    private String key(String source, String eventType, String metricName, Instant start) {
        return source + "|" + eventType + "|" + metricName + "|" + start;
    }

    private record Window(Instant start, Instant end) {}
}
