package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldRangeDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void valueInsideBothBoundsProducesNoAlert() {
        Map<String, FieldRangeDetector.Bounds> bounds = bounds(
                entry("price", 0.0, 1_000_000.0));
        FieldRangeDetector detector = new FieldRangeDetector(mapper, bounds);

        List<DraftAlert> alerts = detector.detect(ctx("e1", Map.of("price", 99.5)));

        assertThat(alerts).isEmpty();
    }

    @Test
    void valueBelowMinProducesWarnAlert() {
        Map<String, FieldRangeDetector.Bounds> bounds = bounds(
                entry("price", 0.0, 1_000_000.0));
        FieldRangeDetector detector = new FieldRangeDetector(mapper, bounds);

        List<DraftAlert> alerts = detector.detect(ctx("e2", Map.of("price", -1.0)));

        assertThat(alerts).hasSize(1);
        DraftAlert a = alerts.get(0);
        assertThat(a.type()).isEqualTo(AlertType.FIELD_OUT_OF_RANGE);
        assertThat(a.severity()).isEqualTo(Severity.WARN);
        assertThat(a.fieldPath()).isEqualTo("price");
        assertThat(a.evidence().get("value").asDouble()).isEqualTo(-1.0);
        assertThat(a.evidence().get("min").asDouble()).isEqualTo(0.0);
        assertThat(a.evidence().get("max").asDouble()).isEqualTo(1_000_000.0);
    }

    @Test
    void valueAboveMaxProducesWarnAlert() {
        Map<String, FieldRangeDetector.Bounds> bounds = bounds(
                entry("quantity", 1.0, 100.0));
        FieldRangeDetector detector = new FieldRangeDetector(mapper, bounds);

        List<DraftAlert> alerts = detector.detect(ctx("e3", Map.of("quantity", 2_000_000)));

        assertThat(alerts).hasSize(1);
        DraftAlert a = alerts.get(0);
        assertThat(a.type()).isEqualTo(AlertType.FIELD_OUT_OF_RANGE);
        assertThat(a.evidence().get("value").asInt()).isEqualTo(2_000_000);
    }

    @Test
    void boundaryValuesAreInclusive() {
        Map<String, FieldRangeDetector.Bounds> bounds = bounds(
                entry("score", 0.0, 100.0));
        FieldRangeDetector detector = new FieldRangeDetector(mapper, bounds);

        assertThat(detector.detect(ctx("lo", Map.of("score", 0.0)))).isEmpty();
        assertThat(detector.detect(ctx("hi", Map.of("score", 100.0)))).isEmpty();
    }

    @Test
    void missingOrNullFieldProducesNoAlert() {
        Map<String, FieldRangeDetector.Bounds> bounds = bounds(
                entry("price", 0.0, 1_000_000.0));
        FieldRangeDetector detector = new FieldRangeDetector(mapper, bounds);

        assertThat(detector.detect(ctx("missing", Map.of("other", 1)))).isEmpty();
        java.util.HashMap<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("price", null);
        assertThat(detector.detect(ctx("nullVal", withNull))).isEmpty();
    }

    @Test
    void multipleFieldsAreEachChecked() {
        Map<String, FieldRangeDetector.Bounds> bounds = bounds(
                entry("price", 0.0, 1_000_000.0),
                entry("quantity", 1.0, 100.0));
        FieldRangeDetector detector = new FieldRangeDetector(mapper, bounds);

        List<DraftAlert> alerts = detector.detect(ctx("multi",
                Map.of("price", 10.0, "quantity", 9999)));

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).fieldPath()).isEqualTo("quantity");
    }

    @Test
    void noConfiguredBoundsProducesNoAlerts() {
        FieldRangeDetector detector = new FieldRangeDetector(mapper, Map.of());

        assertThat(detector.detect(ctx("any", Map.of("price", -5)))).isEmpty();
    }

    private static Map<String, FieldRangeDetector.Bounds> bounds(Map.Entry<String, FieldRangeDetector.Bounds>... entries) {
        Map<String, FieldRangeDetector.Bounds> m = new LinkedHashMap<>();
        for (var e : entries) m.put(e.getKey(), e.getValue());
        return m;
    }

    private static Map.Entry<String, FieldRangeDetector.Bounds> entry(String name, Double min, Double max) {
        FieldRangeDetector.Bounds b = new FieldRangeDetector.Bounds();
        b.setMin(min);
        b.setMax(max);
        return Map.entry(name, b);
    }

    private DetectionContext ctx(String eventId, Map<String, Object> payload) {
        DataEvent e = new DataEvent(eventId, "demo-api", "market_tick",
                Instant.parse("2026-05-25T10:00:00Z"), payload);
        return new DetectionContext(e, "hash-" + eventId, Instant.parse("2026-05-25T10:00:01Z"));
    }
}