package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldFormatDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void valueMatchingPatternProducesNoAlert() {
        FieldFormatDetector detector = new FieldFormatDetector(mapper, "email=^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$");

        List<DraftAlert> alerts = detector.detect(ctx("e1", Map.of("email", "alice@example.com")));

        assertThat(alerts).isEmpty();
    }

    @Test
    void valueNotMatchingPatternProducesWarnAlert() {
        FieldFormatDetector detector = new FieldFormatDetector(mapper, "email=^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$");

        List<DraftAlert> alerts = detector.detect(ctx("e2", Map.of("email", "not-an-email")));

        assertThat(alerts).hasSize(1);
        DraftAlert a = alerts.get(0);
        assertThat(a.type()).isEqualTo(AlertType.FIELD_FORMAT_MISMATCH);
        assertThat(a.severity()).isEqualTo(Severity.WARN);
        assertThat(a.fieldPath()).isEqualTo("email");
        assertThat(a.evidence().get("reason").asText()).isEqualTo("PATTERN_MISMATCH");
        assertThat(a.evidence().get("value").asText()).isEqualTo("not-an-email");
    }

    @Test
    void nullOrMissingFieldProducesNoAlert() {
        FieldFormatDetector detector = new FieldFormatDetector(mapper, "email=^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$");

        assertThat(detector.detect(ctx("missing", Map.of("other", "x")))).isEmpty();
        java.util.HashMap<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("email", null);
        assertThat(detector.detect(ctx("null", withNull))).isEmpty();
    }

    @Test
    void emptyValueDoesNotMatchAndProducesAlert() {
        FieldFormatDetector detector = new FieldFormatDetector(mapper, "sku=^SKU-[0-9]{6}$");

        List<DraftAlert> alerts = detector.detect(ctx("e4", Map.of("sku", "")));

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).fieldPath()).isEqualTo("sku");
    }

    @Test
    void multiplePatternsEachChecked() {
        FieldFormatDetector detector = new FieldFormatDetector(mapper,
                "email=^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$,sku=^SKU-[0-9]{6}$");

        List<DraftAlert> alerts = detector.detect(ctx("multi",
                Map.of("email", "bad", "sku", "SKU-000123")));

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).fieldPath()).isEqualTo("email");
    }

    @Test
    void emptyConfigProducesNoAlerts() {
        FieldFormatDetector detector = new FieldFormatDetector(mapper, "");

        assertThat(detector.detect(ctx("any", Map.of("email", "anything")))).isEmpty();
    }

    @Test
    void nonStringValueProducesAlert() {
        FieldFormatDetector detector = new FieldFormatDetector(mapper, "code=^[A-Z]+$");

        List<DraftAlert> alerts = detector.detect(ctx("e5", Map.of("code", 42)));

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).evidence().get("reason").asText()).isEqualTo("NOT_A_STRING");
    }

    private DetectionContext ctx(String eventId, Map<String, Object> payload) {
        DataEvent e = new DataEvent(eventId, "demo-api", "user_signup",
                Instant.parse("2026-05-25T10:00:00Z"), payload);
        return new DetectionContext(e, "hash-" + eventId, Instant.parse("2026-05-25T10:00:01Z"));
    }
}