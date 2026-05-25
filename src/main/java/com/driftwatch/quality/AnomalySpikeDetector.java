package com.driftwatch.quality;

import com.driftwatch.quality.window.MetricWindowRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Order(50)
public class AnomalySpikeDetector implements QualityDetector {

    static final String EVENT_COUNT_METRIC = "EVENT_COUNT";

    private final MetricWindowRecorder metrics;
    private final ObjectMapper objectMapper;
    private final int baselineWindows;
    private final int minHistoryWindows;
    private final double spikeRatio;
    private final int minCurrentCount;

    public AnomalySpikeDetector(MetricWindowRecorder metrics,
                                ObjectMapper objectMapper,
                                @Value("${driftwatch.detector.anomaly-spike.baseline-windows:2}") int baselineWindows,
                                @Value("${driftwatch.detector.anomaly-spike.min-history-windows:2}") int minHistoryWindows,
                                @Value("${driftwatch.detector.anomaly-spike.ratio-threshold:3.0}") double spikeRatio,
                                @Value("${driftwatch.detector.anomaly-spike.min-current-count:5}") int minCurrentCount) {
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.baselineWindows = baselineWindows;
        this.minHistoryWindows = minHistoryWindows;
        this.spikeRatio = spikeRatio;
        this.minCurrentCount = minCurrentCount;
    }

    @Override
    public List<DraftAlert> detect(DetectionContext ctx) {
        Instant metricTime = ctx.event().eventTimestamp();
        MetricWindowRecorder.MetricUpdate current = metrics.increment(
                ctx.event().source(),
                ctx.event().eventType(),
                EVENT_COUNT_METRIC,
                metricTime,
                1.0d);

        List<MetricWindowRecorder.MetricSnapshot> history = metrics.recentCompleted(
                ctx.event().source(),
                ctx.event().eventType(),
                EVENT_COUNT_METRIC,
                metricTime,
                baselineWindows);

        if (history.size() < minHistoryWindows) {
            return List.of();
        }

        double baseline = history.stream().mapToDouble(MetricWindowRecorder.MetricSnapshot::value).average().orElse(0.0d);
        if (baseline <= 0.0d) {
            return List.of();
        }

        double previousRatio = current.previousValue() / baseline;
        double currentRatio = current.currentValue() / baseline;
        if (current.currentValue() < minCurrentCount || previousRatio > spikeRatio || currentRatio <= spikeRatio) {
            return List.of();
        }

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("window_start", current.windowStart().toString());
        evidence.put("window_end", current.windowEnd().toString());
        evidence.put("baseline", baseline);
        evidence.put("current_value", current.currentValue());
        evidence.put("ratio", currentRatio);
        evidence.put("threshold", spikeRatio);
        evidence.put("history_windows", history.size());

        return List.of(new DraftAlert(
                AlertType.ANOMALY_SPIKE,
                Severity.WARN,
                ctx.event().source(),
                ctx.event().eventType(),
                null,
                "Anomaly spike in " + ctx.event().eventType()
                        + " window (" + Math.round(current.currentValue()) + " vs baseline "
                        + String.format(java.util.Locale.ROOT, "%.2f", baseline) + ")",
                evidence
        ));
    }
}
