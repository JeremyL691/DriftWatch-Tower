package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.driftwatch.quality.schema.SchemaBaselineProvider;
import com.driftwatch.quality.window.MetricWindowRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NullSpikeDetectorTest {

    private final InMemoryMetricWindowRecorder metrics = new InMemoryMetricWindowRecorder(Duration.ofMinutes(1));
    private final SchemaBaselineProvider baselineProvider = eventType -> Map.of("bid", "NUMBER", "ask", "NUMBER");
    private final NullSpikeDetector detector = new NullSpikeDetector(
            metrics, baselineProvider, new ObjectMapper(), 0.5d, 3);

    @Test
    void repeatedMissingFieldCrossesThresholdAndAlerts() {
        Instant now = Instant.parse("2026-05-25T10:00:00Z");

        assertThat(detector.detect(ctx("e1", now, Map.of("bid", 100.0, "ask", 101.0)))).isEmpty();
        assertThat(detector.detect(ctx("e2", now.plusSeconds(1), Map.of("bid", 100.0)))).isEmpty();

        List<DraftAlert> alerts = detector.detect(ctx("e3", now.plusSeconds(2), Map.of("bid", 100.0)));

        assertThat(alerts).hasSize(1);
        DraftAlert alert = alerts.get(0);
        assertThat(alert.type()).isEqualTo(AlertType.NULL_SPIKE);
        assertThat(alert.fieldPath()).isEqualTo("ask");
        assertThat(alert.evidence().get("null_count").asInt()).isEqualTo(2);
        assertThat(alert.evidence().get("total_count").asInt()).isEqualTo(3);
    }

    @Test
    void detectorDoesNothingWithoutBaselineFields() {
        NullSpikeDetector noBaselineDetector = new NullSpikeDetector(
                metrics, eventType -> Map.of(), new ObjectMapper(), 0.5d, 3);

        assertThat(noBaselineDetector.detect(ctx("e4", Instant.parse("2026-05-25T10:01:00Z"), Map.of("bid", 100.0))))
                .isEmpty();
    }

    private DetectionContext ctx(String eventId, Instant timestamp, Map<String, Object> payload) {
        DataEvent event = new DataEvent(eventId, "demo-api", "demo_null_event", timestamp, payload);
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
            return List.of();
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
