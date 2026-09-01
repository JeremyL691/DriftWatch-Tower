package com.driftwatch.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flags numeric payload fields whose value falls outside a configured [min, max] interval
 * (e.g. price &lt; 0, quantity &gt; 1_000_000). Bounds are externalized via
 * {@code driftwatch.detector.field-range.fields}, e.g.:
 *
 * <pre>
 *   driftwatch.detector.field-range.fields.price.min=0
 *   driftwatch.detector.field-range.fields.price.max=1000000
 * </pre>
 *
 * Boundaries are inclusive. Missing/null/non-numeric fields are skipped — other detectors
 * cover nulls and schema drift.
 */
@Component
@Order(60)
public class FieldRangeDetector implements QualityDetector {

    private final ObjectMapper objectMapper;
    private final Map<String, Bounds> bounds;

    @Autowired
    public FieldRangeDetector(ObjectMapper objectMapper, FieldRangeProperties properties) {
        this(objectMapper, properties.getFields());
    }

    public FieldRangeDetector(ObjectMapper objectMapper, Map<String, Bounds> bounds) {
        this.objectMapper = objectMapper;
        this.bounds = bounds == null ? Map.of() : bounds;
    }

    @Override
    public List<DraftAlert> detect(DetectionContext ctx) {
        if (bounds.isEmpty()) {
            return List.of();
        }
        JsonNode payload = objectMapper.valueToTree(ctx.event().payload());
        return bounds.entrySet().stream()
                .flatMap(entry -> checkField(ctx, payload, entry.getKey(), entry.getValue()).stream())
                .toList();
    }

    private List<DraftAlert> checkField(DetectionContext ctx, JsonNode payload, String fieldPath, Bounds b) {
        JsonNode node = payload.get(fieldPath);
        if (node == null || node.isNull() || !node.isNumber()) {
            return List.of();
        }
        double value = node.doubleValue();
        if (b.inRange(value)) {
            return List.of();
        }
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("field_path", fieldPath);
        evidence.put("value", value);
        evidence.put("min", b.min);
        evidence.put("max", b.max);
        String reason = b.max == null
                ? value + " < " + b.min
                : b.min == null
                    ? value + " > " + b.max
                    : value + " outside [" + b.min + ", " + b.max + "]";
        return List.of(new DraftAlert(
                AlertType.FIELD_OUT_OF_RANGE,
                Severity.WARN,
                ctx.event().source(),
                ctx.event().eventType(),
                fieldPath,
                "Field " + fieldPath + " out of range in " + ctx.event().eventType() + " (" + reason + ")",
                evidence
        ));
    }

    /** Inclusive bounds; either side may be null to leave the bound open. */
    public static class Bounds {
        private Double min;
        private Double max;

        public Double getMin() { return min; }
        public void setMin(Double min) { this.min = min; }
        public Double getMax() { return max; }
        public void setMax(Double max) { this.max = max; }

        boolean inRange(double value) {
            if (min != null && value < min) return false;
            if (max != null && value > max) return false;
            return true;
        }
    }
}
