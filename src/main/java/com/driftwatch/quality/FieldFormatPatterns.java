package com.driftwatch.quality;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses the {@code field=regex} specification used by {@link FieldFormatDetector}.
 *
 * <p>Parsing is separated from the detector so the configuration can be validated at startup:
 * an invalid specification fails the application with the offending entry instead of throwing
 * while events are being processed.
 */
public final class FieldFormatPatterns {

    private FieldFormatPatterns() {
    }

    /** @throws IllegalArgumentException naming the bad entry when the specification is invalid */
    public static Map<String, Pattern> parse(String spec) {
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return compiled;
        }
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0 || equals == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "field-format entry '" + trimmed + "' must look like field=regex");
            }
            String field = trimmed.substring(0, equals).trim();
            String regex = trimmed.substring(equals + 1).trim();
            if (field.isEmpty() || regex.isEmpty()) {
                throw new IllegalArgumentException(
                        "field-format entry '" + trimmed + "' must have a non-empty field and regex");
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