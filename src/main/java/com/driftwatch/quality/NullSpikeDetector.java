package com.driftwatch.quality;

import com.driftwatch.quality.schema.SchemaBaselineProvider;
import com.driftwatch.quality.schema.SchemaInferrer;
import com.driftwatch.quality.window.MetricWindowRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Order(40)
public class NullSpikeDetector implements QualityDetector {

    private final MetricWindowRecorder metrics;
    private final SchemaBaselineProvider baselineProvider;
    private final ObjectMapper objectMapper;
    private final double threshold;
    private final int minSamples;

    public NullSpikeDetector(MetricWindowRecorder metrics,
                             SchemaBaselineProvider baselineProvider,
                             ObjectMapper objectMapper,
                             @Value("${driftwatch.detector.null-spike.threshold:0.2}") double threshold,
                             @Value("${driftwatch.detector.null-spike.min-samples:5}") int minSamples) {
        this.metrics = metrics;
        this.baselineProvider = baselineProvider;
        this.objectMapper = objectMapper;
        this.threshold = threshold;
        this.minSamples = minSamples;
    }

    @Override
    public List<DraftAlert> detect(DetectionContext ctx) {
        Map<String, String> expectedFields = baselineProvider.activeLeafFieldTypes(ctx.event().eventType());
        if (expectedFields.isEmpty()) {
            return List.of();
        }

        Map<String, String> observedFields = SchemaInferrer.infer(objectMapper.valueToTree(ctx.event().payload()));
        Instant metricTime = ctx.event().eventTimestamp();
        List<DraftAlert> alerts = new ArrayList<>();

        for (String fieldPath : expectedFields.keySet()) {
            MetricWindowRecorder.MetricUpdate totals = metrics.increment(
                    ctx.event().source(),
                    ctx.event().eventType(),
                    totalMetric(fieldPath),
                    metricTime,
                    1.0d);

            boolean nullish = !observedFields.containsKey(fieldPath) || "NULL".equals(observedFields.get(fieldPath));
            MetricWindowRecorder.MetricUpdate nulls = metrics.increment(
                    ctx.event().source(),
                    ctx.event().eventType(),
                    nullMetric(fieldPath),
                    metricTime,
                    nullish ? 1.0d : 0.0d);

            double previousRate = totals.previousValue() <= 0.0d ? 0.0d : nulls.previousValue() / totals.previousValue();
            double currentRate = nulls.currentValue() / totals.currentValue();
            metrics.set(ctx.event().source(), ctx.event().eventType(), rateMetric(fieldPath), metricTime, currentRate);

            if (totals.currentValue() >= minSamples && previousRate <= threshold && currentRate > threshold) {
                alerts.add(alertFor(ctx, fieldPath, totals, nulls, currentRate));
            }
        }

        return alerts;
    }

    private DraftAlert alertFor(DetectionContext ctx,
                                String fieldPath,
                                MetricWindowRecorder.MetricUpdate totals,
                                MetricWindowRecorder.MetricUpdate nulls,
                                double currentRate) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("field_path", fieldPath);
        evidence.put("window_start", totals.windowStart().toString());
        evidence.put("window_end", totals.windowEnd().toString());
        evidence.put("null_count", Math.round(nulls.currentValue()));
        evidence.put("total_count", Math.round(totals.currentValue()));
        evidence.put("null_rate", currentRate);
        evidence.put("threshold", threshold);
        return new DraftAlert(
                AlertType.NULL_SPIKE,
                Severity.WARN,
                ctx.event().source(),
                ctx.event().eventType(),
                fieldPath,
                "Null spike for " + fieldPath + " in " + ctx.event().eventType()
                        + " (" + Math.round(nulls.currentValue()) + "/" + Math.round(totals.currentValue()) + ")",
                evidence
        );
    }

    static String totalMetric(String fieldPath) {
        return "NULL_TOTAL:" + fieldPath;
    }

    static String nullMetric(String fieldPath) {
        return "NULL_COUNT:" + fieldPath;
    }

    static String rateMetric(String fieldPath) {
        return "NULL_RATE:" + fieldPath;
    }
}
