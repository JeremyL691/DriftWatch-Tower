package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LateEventDetectorTest {

    private final LateEventDetector detector =
            new LateEventDetector(new ObjectMapper(), Duration.ofMinutes(5));

    @Test
    void onTimeEventProducesNoAlert() {
        Instant now = Instant.parse("2026-05-25T10:00:00Z");
        DataEvent e = new DataEvent("e1", "binance", "market_tick",
                now.minusSeconds(30), Map.of("k", "v"));

        List<DraftAlert> alerts = detector.detect(new DetectionContext(e, "hash", now));

        assertThat(alerts).isEmpty();
    }

    @Test
    void lateEventProducesLateAlertWithEvidence() {
        Instant now = Instant.parse("2026-05-25T10:00:00Z");
        DataEvent e = new DataEvent("e2", "binance", "market_tick",
                now.minusSeconds(600), Map.of("k", "v"));

        List<DraftAlert> alerts = detector.detect(new DetectionContext(e, "hash", now));

        assertThat(alerts).hasSize(1);
        DraftAlert a = alerts.get(0);
        assertThat(a.type()).isEqualTo(AlertType.LATE_EVENT);
        assertThat(a.evidence().get("lateness_seconds").asLong()).isEqualTo(600);
        assertThat(a.evidence().get("threshold").asText()).isEqualTo("PT5M");
    }

    @Test
    void severelyLateEventBecomesWarn() {
        Instant now = Instant.parse("2026-05-25T10:00:00Z");
        DataEvent e = new DataEvent("e3", "binance", "market_tick",
                now.minus(Duration.ofHours(2)), Map.of("k", "v"));

        DraftAlert a = detector.detect(new DetectionContext(e, "hash", now)).get(0);

        assertThat(a.severity()).isEqualTo(Severity.WARN);
    }
}
