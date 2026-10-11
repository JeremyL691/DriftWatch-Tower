package com.driftwatch.config;

import com.driftwatch.quality.FieldFormatPatterns;
import com.driftwatch.quality.FieldRangeDetector;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Complete nonsecret management document. Durations are integer milliseconds. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ManagementConfiguration(
        long duplicate_window_ms, long late_threshold_ms, long github_late_threshold_ms,
        double null_threshold, int null_min_samples,
        int anomaly_baseline_windows, int anomaly_min_history_windows,
        double anomaly_ratio, int anomaly_min_count,
        long window_size_ms, long grace_ms, long future_tolerance_ms, long state_retention_ms,
        Map<String, Range> ranges, String format_patterns,
        boolean github_enabled, String github_repository, long github_poll_interval_ms,
        int github_max_pages, int github_page_size) {

    public static final Set<String> LIVE_FIELDS = Set.of("late_threshold_ms", "github_late_threshold_ms",
            "null_threshold", "null_min_samples", "anomaly_ratio", "anomaly_min_count", "ranges", "format_patterns");

    public record Range(Double min, Double max) {}

    public ManagementConfiguration {
        if (ranges != null) ranges = Map.copyOf(ranges);
    }

    public static ManagementConfiguration from(DriftwatchProperties p) {
        var d = p.detector(); var m = p.metrics(); var g = p.source().github();
        Map<String, Range> ranges = new LinkedHashMap<>();
        d.fieldRange().fields().forEach((name, bounds) -> ranges.put(name, new Range(bounds.getMin(), bounds.getMax())));
        return new ManagementConfiguration(d.duplicate().payloadWindow().toMillis(), d.late().threshold().toMillis(),
                d.late().githubThreshold().toMillis(), d.nullSpike().threshold(), d.nullSpike().minSamples(),
                d.anomalySpike().baselineWindows(), d.anomalySpike().minHistoryWindows(),
                d.anomalySpike().ratioThreshold(), d.anomalySpike().minCurrentCount(), m.windowSize().toMillis(),
                m.grace().toMillis(), m.futureTolerance().toMillis(), m.stateRetention().toMillis(), ranges,
                d.fieldFormat().patterns() == null ? "" : d.fieldFormat().patterns(), g.enabled(), g.repository(),
                g.pollInterval().toMillis(), g.maxPages(), g.pageSize());
    }

    public void validate() {
        duration("duplicate_window_ms", duplicate_window_ms);
        duration("late_threshold_ms", late_threshold_ms);
        duration("github_late_threshold_ms", github_late_threshold_ms);
        duration("window_size_ms", window_size_ms);
        require(window_size_ms >= 1000 && window_size_ms % 1000 == 0,
                "window_size_ms must be a whole number of seconds (at least 1000)");
        duration("grace_ms", grace_ms);
        duration("future_tolerance_ms", future_tolerance_ms); duration("state_retention_ms", state_retention_ms);
        require(duplicate_window_ms >= window_size_ms, "duplicate_window_ms must cover window_size_ms");
        require(github_late_threshold_ms >= late_threshold_ms, "github_late_threshold_ms must cover late_threshold_ms");
        require(Double.isFinite(null_threshold) && null_threshold > 0 && null_threshold <= 1, "null_threshold must be finite in (0,1]");
        bounded("null_min_samples", null_min_samples, 1, 1000000);
        bounded("anomaly_baseline_windows", anomaly_baseline_windows, 1, 1000);
        bounded("anomaly_min_history_windows", anomaly_min_history_windows, 1, anomaly_baseline_windows);
        require(Double.isFinite(anomaly_ratio) && anomaly_ratio > 1 && anomaly_ratio <= 1000000, "anomaly_ratio must be finite in (1,1000000]");
        bounded("anomaly_min_count", anomaly_min_count, 1, 1000000);
        require(state_retention_ms >= grace_ms + window_size_ms * (anomaly_baseline_windows + 1L),
                "state_retention_ms must cover grace + (baseline windows + 1) * window size");
        require(ranges != null && ranges.size() <= 50, "ranges must contain at most 50 fields");
        ranges.forEach((field, r) -> {
            field(field);
            require(r != null && (r.min() != null || r.max() != null), "range needs a bound");
            require((r.min() == null || Double.isFinite(r.min())) && (r.max() == null || Double.isFinite(r.max())), "range bounds must be finite");
            require(r.min() == null || r.max() == null || r.min() <= r.max(), "range min must not exceed max");
        });
        require(format_patterns != null && format_patterns.length() <= 8192, "format_patterns must be at most 8192 characters");
        var parsed = FieldFormatPatterns.parse(format_patterns);
        require(parsed.size() <= 50, "format_patterns must contain at most 50 fields");
        parsed.forEach((name, pattern) -> { field(name); require(pattern.pattern().length() <= 512, "regex must be at most 512 characters"); });
        require(github_repository != null && github_repository.length() <= 201
                && github_repository.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]{1,100}")
                && !github_repository.endsWith("/.") && !github_repository.endsWith("/.."), "github_repository must be owner/repository");
        require(github_poll_interval_ms >= 300000 && github_poll_interval_ms <= 86400000, "github_poll_interval_ms must be 300000..86400000");
        bounded("github_max_pages", github_max_pages, 1, 10); bounded("github_page_size", github_page_size, 1, 100);
    }

    /** Keep restart-dependent values from this process's startup; overlay only live-safe fields. */
    public ManagementConfiguration withLive(ManagementConfiguration saved) {
        return new ManagementConfiguration(duplicate_window_ms, saved.late_threshold_ms, saved.github_late_threshold_ms,
                saved.null_threshold, saved.null_min_samples, anomaly_baseline_windows, anomaly_min_history_windows,
                saved.anomaly_ratio, saved.anomaly_min_count, window_size_ms, grace_ms, future_tolerance_ms,
                state_retention_ms, saved.ranges, saved.format_patterns, github_enabled, github_repository,
                github_poll_interval_ms, github_max_pages, github_page_size);
    }

    public Map<String, FieldRangeDetector.Bounds> detectorBounds() {
        Map<String, FieldRangeDetector.Bounds> result = new LinkedHashMap<>();
        ranges.forEach((name, range) -> { var b = new FieldRangeDetector.Bounds(); b.setMin(range.min()); b.setMax(range.max()); result.put(name, b); });
        return Map.copyOf(result);
    }

    /** Only nonsecret fields are replaced; tokens, destinations and infrastructure stay environment-owned. */
    public DriftwatchProperties overlay(DriftwatchProperties p) {
        var old = p.source().github();
        var g = new DriftwatchProperties.Source.Github(github_enabled, github_repository, old.baseUrl(),
                Duration.ofMillis(github_poll_interval_ms), github_max_pages, github_page_size,
                old.connectTimeout(), old.requestTimeout(), old.maxFirstReadRecords(), old.token(),
                old.userAgentVersion(), old.allowNonOfficialBaseUrl(), old.schedulerEnabled());
        var d = new DriftwatchProperties.Detector(new DriftwatchProperties.Detector.Duplicate(Duration.ofMillis(duplicate_window_ms)),
                new DriftwatchProperties.Detector.Late(Duration.ofMillis(late_threshold_ms), Duration.ofMillis(github_late_threshold_ms)),
                new DriftwatchProperties.Detector.NullSpike(null_threshold, null_min_samples),
                new DriftwatchProperties.Detector.AnomalySpike(anomaly_baseline_windows, anomaly_min_history_windows, anomaly_ratio, anomaly_min_count),
                new DriftwatchProperties.Detector.FieldRange(detectorBounds()), new DriftwatchProperties.Detector.FieldFormat(format_patterns));
        return new DriftwatchProperties(d, new DriftwatchProperties.Metrics(Duration.ofMillis(window_size_ms), Duration.ofMillis(grace_ms),
                Duration.ofMillis(future_tolerance_ms), Duration.ofMillis(state_retention_ms)), p.streams(), p.sourceHealth(), p.security(),
                new DriftwatchProperties.Source(g), p.bridge());
    }

    private static void duration(String field, long value) { require(value >= 1 && value <= 2592000000L, field + " must be 1..2592000000 milliseconds"); }
    private static void bounded(String field, int value, int min, int max) { require(value >= min && value <= max, field + " is out of range"); }
    private static void field(String name) { require(name != null && name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}"), "invalid rule field name"); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
