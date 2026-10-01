package com.driftwatch.config;

import com.driftwatch.quality.FieldFormatPatterns;
import com.driftwatch.quality.FieldRangeDetector;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Central configuration for the application, bound from the {@code driftwatch.*} keys.
 *
 * <p>Numeric ranges are enforced with bean validation so a bad value fails startup with the
 * offending key named. Relations between windows, grace, and retention that bean validation
 * cannot express are checked in {@link #validateRelations()}. Error messages never include
 * secret values.
 */
@ConfigurationProperties(prefix = "driftwatch")
@Validated
public record DriftwatchProperties(
        @Valid @DefaultValue Detector detector,
        @Valid @DefaultValue Metrics metrics,
        @Valid @DefaultValue Streams streams,
        @Valid @DefaultValue SourceHealth sourceHealth,
        @Valid @DefaultValue Security security,
        @Valid @DefaultValue Source source
) {

    public record Detector(
            @Valid @DefaultValue Duplicate duplicate,
            @Valid @DefaultValue Late late,
            @Valid @DefaultValue NullSpike nullSpike,
            @Valid @DefaultValue AnomalySpike anomalySpike,
            @Valid @DefaultValue FieldRange fieldRange,
            @Valid @DefaultValue FieldFormat fieldFormat
    ) {
        public record Duplicate(@NotNull Duration payloadWindow) {}

        public record Late(
                @NotNull Duration threshold,
                /** GitHub upstream can lag by hours; the source-specific late threshold is larger. */
                @NotNull Duration githubThreshold
        ) {}

        public record NullSpike(
                @DecimalMin(value = "0.0", inclusive = false)
                @DecimalMax("1.0")
                double threshold,
                @Min(1) int minSamples
        ) {}

        public record AnomalySpike(
                @Min(1) int baselineWindows,
                @Min(1) int minHistoryWindows,
                @DecimalMin(value = "1.0", inclusive = false) double ratioThreshold,
                @Min(1) int minCurrentCount
        ) {}

        public record FieldRange(@NotNull Map<String, FieldRangeDetector.Bounds> fields) {}

        public record FieldFormat(String patterns) {}
    }

    public record Metrics(
            @NotNull Duration windowSize,
            @NotNull Duration grace,
            /** Events further in the future than this are excluded from windows. */
            @NotNull Duration futureTolerance,
            /** Must cover grace plus the anomaly baseline span. */
            @NotNull Duration stateRetention
    ) {}

    public record Streams(@DefaultValue("true") boolean enabled) {}

    public record SourceHealth(
            @NotNull Duration defaultStaleAfter,
            @NotNull Duration rssStaleAfter,
            @NotNull Duration pipelineStaleAfter,
            /** Collector is considered lost after max(this, 2x its legal poll interval). */
            @NotNull Duration collectorLostAfter
    ) {}

    public record Security(
            @Valid @DefaultValue Admin admin,
            @DefaultValue List<String> ingestTokens,
            /**
             * When true (selfhost profile), missing or weak admin credentials fail startup.
             * Tests and local development set this to false.
             */
            @DefaultValue("false") boolean requireStrongCredentials
    ) {
        /** Blank entries are dropped so an unset env var cannot become an empty token. */
        public Security {
            ingestTokens = ingestTokens == null ? List.of()
                    : ingestTokens.stream().filter(token -> token != null && !token.isBlank()).toList();
        }

        public record Admin(
                @NotBlank String username,
                @NotNull String password
        ) {}
    }

    public record Source(@Valid @DefaultValue Github github) {
        public record Github(@DefaultValue("false") boolean enabled) {}
    }

    @PostConstruct
    void validateRelations() {
        Metrics m = metrics;
        requirePositive("driftwatch.metrics.window-size", m.windowSize());
        requirePositive("driftwatch.metrics.grace", m.grace());
        requirePositive("driftwatch.metrics.future-tolerance", m.futureTolerance());
        requirePositive("driftwatch.metrics.state-retention", m.stateRetention());
        requirePositive("driftwatch.detector.duplicate.payload-window", detector.duplicate().payloadWindow());
        requirePositive("driftwatch.detector.late.threshold", detector.late().threshold());
        requirePositive("driftwatch.detector.late.github-threshold", detector.late().githubThreshold());
        requirePositive("driftwatch.source-health.default-stale-after", sourceHealth.defaultStaleAfter());
        requirePositive("driftwatch.source-health.rss-stale-after", sourceHealth.rssStaleAfter());
        requirePositive("driftwatch.source-health.pipeline-stale-after", sourceHealth.pipelineStaleAfter());
        requirePositive("driftwatch.source-health.collector-lost-after", sourceHealth.collectorLostAfter());

        if (detector.duplicate().payloadWindow().compareTo(m.windowSize()) < 0) {
            throw new IllegalStateException(
                    "driftwatch.detector.duplicate.payload-window must be >= driftwatch.metrics.window-size");
        }
        if (detector.late().githubThreshold().compareTo(detector.late().threshold()) < 0) {
            throw new IllegalStateException(
                    "driftwatch.detector.late.github-threshold must be >= driftwatch.detector.late.threshold");
        }
        if (detector.anomalySpike().minHistoryWindows() > detector.anomalySpike().baselineWindows()) {
            throw new IllegalStateException(
                    "driftwatch.detector.anomaly-spike.min-history-windows must be <= baseline-windows");
        }
        Duration requiredRetention = m.grace()
                .plus(m.windowSize().multipliedBy(detector.anomalySpike().baselineWindows() + 1L));
        if (m.stateRetention().compareTo(requiredRetention) < 0) {
            throw new IllegalStateException("driftwatch.metrics.state-retention must be >= grace + "
                    + "(baseline-windows + 1) * window-size (requires " + requiredRetention + ")");
        }
        if (sourceHealth.rssStaleAfter().compareTo(sourceHealth.defaultStaleAfter()) < 0
                || sourceHealth.pipelineStaleAfter().compareTo(sourceHealth.rssStaleAfter()) < 0) {
            throw new IllegalStateException("driftwatch.source-health stale thresholds must satisfy "
                    + "default <= rss <= pipeline");
        }
        if (sourceHealth.collectorLostAfter().compareTo(sourceHealth.defaultStaleAfter()) < 0) {
            throw new IllegalStateException(
                    "driftwatch.source-health.collector-lost-after must be >= driftwatch.source-health.default-stale-after");
        }
        try {
            FieldFormatPatterns.parse(detector.fieldFormat().patterns());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "driftwatch.detector.field-format.patterns is invalid: " + e.getMessage());
        }
        detector.fieldRange().fields().forEach((field, bounds) -> {
            if (bounds.getMin() != null && bounds.getMax() != null && bounds.getMin() > bounds.getMax()) {
                throw new IllegalStateException("driftwatch.detector.field-range.fields." + field
                        + ".min must be <= .max");
            }
        });
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " must be a positive duration");
        }
    }
}
