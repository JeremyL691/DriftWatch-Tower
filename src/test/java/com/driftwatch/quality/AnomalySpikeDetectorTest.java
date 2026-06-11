package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.driftwatch.support.InMemoryMetricWindowRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

}
