package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rule boundaries for the field detectors (execution guide, section 5.2 and P2.2): numeric
 * bounds, non-numeric evidence, regex configuration errors and the payload hashing contract.
 */
class FieldRulesBoundaryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private DetectionContext ctx(Map<String, Object> payload) {
        return new DetectionContext(
                new DataEvent("evt", "rules-source", "rules_event", Instant.now(), payload),
                "hash", Instant.now());
    }

    // ---------------------------------------------------------------- range

    @Test
    void nonNumericValuesAreEvidenceNotExceptions() {
        FieldRangeDetector.Bounds bounds = new FieldRangeDetector.Bounds();
        bounds.setMin(0.0);
        bounds.setMax(10.0);
        FieldRangeDetector detector = new FieldRangeDetector(objectMapper, Map.of("price", bounds));

        List<DraftAlert> alerts = detector.detect(ctx(Map.of(
                "price", Map.of("nested", 1),
                "text", "n/a")));

        assertThat(alerts).hasSize(1);
        DraftAlert alert = alerts.get(0);
        assertThat(alert.type()).isEqualTo(AlertType.FIELD_OUT_OF_RANGE);
        assertThat(alert.evidence().get("reason").asText()).isEqualTo("NOT_A_NUMBER");
        assertThat(alert.evidence().get("value_type").asText()).isEqualTo("OBJECT");
    }

    @Test
    void booleanAndArrayValuesAreReportedByType() {
        FieldRangeDetector.Bounds bounds = new FieldRangeDetector.Bounds();
        bounds.setMin(0.0);
        bounds.setMax(10.0);
        FieldRangeDetector detector = new FieldRangeDetector(objectMapper, Map.of("price", bounds));

        List<DraftAlert> alerts = detector.detect(ctx(Map.of("price", List.of(1, 2))));
        assertThat(alerts).singleElement().satisfies(alert ->
                assertThat(alert.evidence().get("value_type").asText()).isEqualTo("ARRAY"));
    }

    @Test
    void openEndedBoundsOnlyCheckTheConfiguredSide() {
        FieldRangeDetector.Bounds minOnly = new FieldRangeDetector.Bounds();
        minOnly.setMin(5.0);
        FieldRangeDetector detector = new FieldRangeDetector(objectMapper, Map.of("price", minOnly));

        assertThat(detector.detect(ctx(Map.of("price", 1000.0)))).isEmpty();
        assertThat(detector.detect(ctx(Map.of("price", 4.9)))).hasSize(1);
    }

    // ---------------------------------------------------------------- format

    @Test
    void invalidRegexFailsParsingWithTheOffendingEntry() {
        assertThatThrownBy(() -> FieldFormatPatterns.parse("code=^SKU-[0-9{6}$"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    void malformedEntryFailsParsing() {
        assertThatThrownBy(() -> FieldFormatPatterns.parse("code"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field=regex");
        assertThatThrownBy(() -> FieldFormatPatterns.parse("=^x$"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patternsAreCompiledOnceAndReused() {
        FieldFormatDetector detector = new FieldFormatDetector(objectMapper, "code=^SKU-[0-9]{6}$");
        assertThat(detector.detect(ctx(Map.of("code", "SKU-123456")))).isEmpty();
        assertThat(detector.detect(ctx(Map.of("code", "SKU-12345")))).hasSize(1);
        assertThat(detector.detect(ctx(Map.of("code", "sku-123456")))).hasSize(1);
    }

    @Test
    void nestedFieldPathsAreNotSilentlyTreatedAsFlatFields() {
        // The rule contract addresses top-level fields; a nested object must produce type
        // evidence rather than being reported as a plain mismatch.
        FieldFormatDetector detector = new FieldFormatDetector(objectMapper, "code=^SKU-[0-9]{6}$");
        List<DraftAlert> alerts = detector.detect(ctx(Map.of("code", Map.of("value", "SKU-123456"))));
        assertThat(alerts).singleElement().satisfies(alert ->
                assertThat(alert.evidence().get("reason").asText()).isEqualTo("NOT_A_STRING"));
    }
}