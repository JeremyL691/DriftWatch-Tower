package com.driftwatch.quality.window;

import com.driftwatch.persistence.MetricWindowEntity;
import com.driftwatch.persistence.MetricWindowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class MetricWindowService implements MetricWindowRecorder {

    private final MetricWindowRepository repository;
    private final Duration windowSize;

    public MetricWindowService(MetricWindowRepository repository,
                               @Value("${driftwatch.metrics.window-size:PT1M}") Duration windowSize) {
        this.repository = repository;
        this.windowSize = windowSize;
    }

    @Override
    @Transactional
    public MetricUpdate increment(String source, String eventType, String metricName, Instant observedAt, double delta) {
        WindowBounds bounds = windowFor(observedAt);
        MetricWindowEntity row = loadOrCreate(source, eventType, metricName, bounds);
        double previous = row.getMetricValue();
        row.setMetricValue(previous + delta);
        repository.save(row);
        return new MetricUpdate(bounds.windowStart(), bounds.windowEnd(), previous, row.getMetricValue());
    }

    @Override
    @Transactional
    public MetricUpdate set(String source, String eventType, String metricName, Instant observedAt, double value) {
        WindowBounds bounds = windowFor(observedAt);
        MetricWindowEntity row = loadOrCreate(source, eventType, metricName, bounds);
        double previous = row.getMetricValue();
        row.setMetricValue(value);
        repository.save(row);
        return new MetricUpdate(bounds.windowStart(), bounds.windowEnd(), previous, value);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricSnapshot> recentCompleted(String source, String eventType, String metricName, Instant observedAt, int limit) {
        WindowBounds bounds = windowFor(observedAt);
        return repository.findRecentCompleted(
                        source,
                        eventType,
                        metricName,
                        bounds.windowStart(),
                        PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(row -> new MetricSnapshot(row.getWindowStart(), row.getWindowEnd(), row.getMetricValue()))
                .toList();
    }

    private MetricWindowEntity loadOrCreate(String source, String eventType, String metricName, WindowBounds bounds) {
        return repository.findBySourceAndEventTypeAndWindowStartAndWindowEndAndMetricName(
                        source, eventType, bounds.windowStart(), bounds.windowEnd(), metricName)
                .orElseGet(() -> {
                    MetricWindowEntity row = new MetricWindowEntity();
                    row.setSource(source);
                    row.setEventType(eventType);
                    row.setWindowStart(bounds.windowStart());
                    row.setWindowEnd(bounds.windowEnd());
                    row.setMetricName(metricName);
                    row.setMetricValue(0.0d);
                    return row;
                });
    }

    private WindowBounds windowFor(Instant observedAt) {
        long seconds = windowSize.toSeconds();
        if (seconds <= 0) {
            throw new IllegalStateException("driftwatch.metrics.window-size must be positive");
        }
        long bucket = Math.floorDiv(observedAt.getEpochSecond(), seconds);
        Instant start = Instant.ofEpochSecond(bucket * seconds);
        return new WindowBounds(start, start.plusSeconds(seconds));
    }

    private record WindowBounds(Instant windowStart, Instant windowEnd) {}
}
