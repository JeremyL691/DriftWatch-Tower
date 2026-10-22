package com.driftwatch.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses the {@code field=regex} or JSON {@code {"field": "regex"}} specification
 * used by {@link FieldFormatDetector}.
 *
 * <p>Parsing is separated from the detector so the configuration can be validated at startup:
 * an invalid specification fails the application with the offending entry instead of throwing
 * while events are being processed.
 */
public final class FieldFormatPatterns {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FieldFormatPatterns() {
    }

    /** @throws IllegalArgumentException naming the bad entry when the specification is invalid */
    public static Map<String, Pattern> parse(String spec) {
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return compiled;
        }
        String trimmed = spec.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJson(trimmed);
        }
        for (String entry : spec.split(",")) {
            String item = entry.trim();
            if (item.isEmpty()) {
                continue;
            }
            int equals = item.indexOf('=');
            if (equals <= 0 || equals == item.length() - 1) {
                throw new IllegalArgumentException(
                        "field-format entry '" + item + "' must look like field=regex");
            }
            String field = item.substring(0, equals).trim();
            String regex = item.substring(equals + 1).trim();
            if (field.isEmpty() || regex.isEmpty()) {
                throw new IllegalArgumentException(
                        "field-format entry '" + item + "' must have a non-empty field and regex");
            }
            try {
                compiled.put(field, Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "field-format regex for field '" + field + "' is invalid: " + e.getDescription());
            }
        }
        return compiled;
    }

    private static Map<String, Pattern> parseJson(String json) {
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("field-format JSON specification is invalid: " + e.getOriginalMessage(), e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("field-format JSON specification must be a JSON object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String field = entry.getKey() != null ? entry.getKey().trim() : "";
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || !valueNode.isTextual()) {
                throw new IllegalArgumentException(
                        "field-format regex for field '" + field + "' must be a string");
            }
            String regex = valueNode.asText().trim();
            if (field.isEmpty() || regex.isEmpty()) {
                throw new IllegalArgumentException(
                        "field-format entry for field '" + field + "' must have a non-empty field and regex");
            }
            try {
                compiled.put(field, Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "field-format regex for field '" + field + "' is invalid: " + e.getDescription());
            }
        }
        return compiled;
    }
}