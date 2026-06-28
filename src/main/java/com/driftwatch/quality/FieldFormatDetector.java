package com.driftwatch.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Flags string payload fields whose value does not match a configured regex (e.g. malformed
 * email, bad SKU). Patterns are externalized via a single comma-separated string
 * {@code driftwatch.detector.field-format.patterns} of the form {@code field=regex}, e.g.:
 *
 * <pre>
 *   driftwatch.detector.field-format.patterns: email=^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$,sku=^SKU-[0-9]{6}$
 * </pre>
 *
 * Missing or null fields are skipped — other detectors cover those cases. Empty/missing config
 * disables the detector.
 */
@Component
@Order(70)
public class FieldFormatDetector implements QualityDetector {

    private final ObjectMapper objectMapper;
    private final Map<String, Pattern> patterns;

    public FieldFormatDetector(ObjectMapper objectMapper,
                               @Value("${driftwatch.detector.field-format.patterns:}") String spec) {
        this.objectMapper = objectMapper;
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        if (spec != null && !spec.isBlank()) {
            for (String entry : spec.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0 || eq == trimmed.length() - 1) continue;
                String field = trimmed.substring(0, eq).trim();
                String regex = trimmed.substring(eq + 1).trim();
                if (field.isEmpty() || regex.isEmpty()) continue;
                compiled.put(field, Pattern.compile(regex));
            }
        }
        this.patterns = compiled;
    }

    @Override
    public List<DraftAlert> detect(DetectionContext ctx) {
        if (patterns.isEmpty()) {
            return List.of();
        }
        JsonNode payload = objectMapper.valueToTree(ctx.event().payload());
        return patterns.entrySet().stream()
                .flatMap(entry -> checkField(ctx, payload, entry.getKey(), entry.getValue()).stream())
                .toList();
    }

    private List<DraftAlert> checkField(DetectionContext ctx, JsonNode payload, String fieldPath, Pattern pattern) {
        JsonNode node = payload.get(fieldPath);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isTextual()) {
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.put("field_path", fieldPath);
            evidence.put("reason", "NOT_A_STRING");
            evidence.put("value_type", node.getNodeType().name());
            return List.of(new DraftAlert(
                    AlertType.FIELD_FORMAT_MISMATCH,
                    Severity.WARN,
                    ctx.event().source(),
                    ctx.event().eventType(),
                    fieldPath,
                    "Field " + fieldPath + " is not a string in " + ctx.event().eventType(),
                    evidence
            ));
        }
        String value = node.asText();
        if (pattern.matcher(value).matches()) {
            return List.of();
        }
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("field_path", fieldPath);
        evidence.put("reason", "PATTERN_MISMATCH");
        evidence.put("value", value);
        evidence.put("pattern", pattern.pattern());
        return List.of(new DraftAlert(
                AlertType.FIELD_FORMAT_MISMATCH,
                Severity.WARN,
                ctx.event().source(),
                ctx.event().eventType(),
                fieldPath,
                "Field " + fieldPath + " does not match expected format in " + ctx.event().eventType(),
                evidence
        ));
    }
}