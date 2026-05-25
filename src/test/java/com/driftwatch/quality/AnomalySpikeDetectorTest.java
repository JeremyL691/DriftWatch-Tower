package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.driftwatch.quality.window.MetricWindowRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalySpikeDetectorTest {

    private final InMemoryMetricWindowRecorder metrics = new InMemoryMetricWindowRecorder(Duration.ofMinutes(1));
    private final AnomalySpikeDetector detector = new AnomalySpikeDetector(
            metrics, new ObjectMapper(), 2, 2, 3.0d, 5);

    @Test
    void spikeAcrossWindowsCreatesAlert() {
        Instant now = Instant.parse("2026-05-25T10:02:00Z");

        publishWindow(now.minusSeconds(120), 2);
        publishWindow(now.minusSeconds(60), 2);

        List<DraftAlert> alerts = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            alerts.addAll(detector.detect(ctx("curr-" + i, now.plusSeconds(i))));
        }

        assertThat(alerts).hasSize(1);
        DraftAlert alert = alerts.get(0);
        assertThat(alert.type()).isEqualTo(AlertType.ANOMALY_SPIKE);
        assertThat(alert.evidence().get("baseline").asDouble()).isEqualTo(2.0d);
        assertThat(alert.evidence().get("current_value").asDouble()).isEqualTo(7.0d);
    }

    @Test
    void detectorNeedsHistoryBeforeItAlerts() {
        assertThat(detector.detect(ctx("solo", Instant.parse("2026-05-25T10:00:00Z")))).isEmpty();
    }

    private void publishWindow(Instant base, int count) {
        for (int i = 0; i < count; i++) {
            detector.detect(ctx("hist-" + base.getEpochSecond() + "-" + i, base.plusSeconds(i)));
        }
    }

    private DetectionContext ctx(String eventId, Instant timestamp) {
        DataEvent event = new DataEvent(
                eventId,
                "demo-api",
                "demo_anomaly_event",
                timestamp,
                Map.of("symbol", "BTC/USDT", "seq", eventId)
        );
        return new DetectionContext(event, "hash-" + eventId, timestamp.plusSeconds(1));
    }

    private static final class InMemoryMetricWindowRecorder implements MetricWindowRecorder {

        private final Duration windowSize;
        private final Map<String, Double> values = new HashMap<>();

        private InMemoryMetricWindowRecorder(Duration windowSize) {
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
}
