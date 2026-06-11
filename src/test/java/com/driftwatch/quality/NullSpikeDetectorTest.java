package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.driftwatch.quality.schema.SchemaBaselineProvider;
import com.driftwatch.support.InMemoryMetricWindowRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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

}
